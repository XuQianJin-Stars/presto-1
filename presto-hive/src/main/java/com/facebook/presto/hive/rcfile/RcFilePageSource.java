/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.hive.rcfile;

import com.facebook.presto.hive.HiveColumnHandle;
import com.facebook.presto.hive.HivePartitionKey;
import com.facebook.presto.hive.HiveType;
import com.facebook.presto.hive.HiveUtil;
import com.facebook.presto.spi.ConnectorPageSource;
import com.facebook.presto.spi.Page;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.block.Block;
import com.facebook.presto.spi.block.BlockBuilder;
import com.facebook.presto.spi.block.BlockBuilderStatus;
import com.facebook.presto.spi.block.LazyBlockLoader;
import com.facebook.presto.spi.block.LazyFixedWidthBlock;
import com.facebook.presto.spi.block.LazySliceArrayBlock;
import com.facebook.presto.spi.type.FixedWidthType;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.spi.type.TypeManager;
import com.google.common.base.Charsets;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Ints;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import org.apache.hadoop.hive.ql.io.RCFile;
import org.apache.hadoop.hive.ql.io.RCFile.Reader;
import org.apache.hadoop.hive.serde2.columnar.BytesRefArrayWritable;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.StructObjectInspector;
import org.joda.time.DateTimeZone;
import org.joda.time.format.ISODateTimeFormat;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static com.facebook.presto.hive.HiveBooleanParser.isFalse;
import static com.facebook.presto.hive.HiveBooleanParser.isTrue;
import static com.facebook.presto.hive.HiveErrorCode.HIVE_CURSOR_ERROR;
import static com.facebook.presto.hive.HiveUtil.getTableObjectInspector;
import static com.facebook.presto.hive.HiveUtil.parseHiveTimestamp;
import static com.facebook.presto.hive.NumberParser.parseDouble;
import static com.facebook.presto.hive.NumberParser.parseLong;
import static com.facebook.presto.spi.type.BigintType.BIGINT;
import static com.facebook.presto.spi.type.BooleanType.BOOLEAN;
import static com.facebook.presto.spi.type.DateType.DATE;
import static com.facebook.presto.spi.type.DoubleType.DOUBLE;
import static com.facebook.presto.spi.type.TimestampType.TIMESTAMP;
import static com.facebook.presto.spi.type.VarcharType.VARCHAR;
import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.Maps.uniqueIndex;

public class RcFilePageSource
        implements ConnectorPageSource
{
    private static final int MAX_PAGE_SIZE = 1024;

    private final RCFile.Reader recordReader;
    private final RcFileBlockLoader blockLoader;
    private final long startFilePosition;
    private final long endFilePosition;

    private final List<String> columnNames;
    private final List<Type> types;
    private final List<HiveType> hiveTypes;

    private final ObjectInspector[] fieldInspectors; // DON'T USE THESE UNLESS EXTRACTION WILL BE SLOW ANYWAY

    private final Block[] constantBlocks;
    private final int[] hiveColumnIndexes;

    private int pageId;
    private int currentBatchSize;
    private int positionInBatch;
    private int currentPageSize;

    private boolean closed;

    private final BytesRefArrayWritable[] columnBatch;
    private final boolean[] columnBatchLoaded;

    private long completedBytes;

    public RcFilePageSource(
            Reader recordReader,
            long offset,
            long length,
            RcFileBlockLoader blockLoader,
            Properties splitSchema,
            List<HivePartitionKey> partitionKeys,
            List<HiveColumnHandle> columns,
            DateTimeZone hiveStorageTimeZone,
            TypeManager typeManager)
    {
        this.recordReader = checkNotNull(recordReader, "recordReader is null");
        this.blockLoader = checkNotNull(blockLoader, "blockLoader is null");
        checkNotNull(splitSchema, "splitSchema is null");
        checkNotNull(partitionKeys, "partitionKeys is null");
        checkNotNull(columns, "columns is null");
        checkNotNull(hiveStorageTimeZone, "hiveStorageTimeZone is null");
        checkNotNull(typeManager, "typeManager is null");

        // seek to start
        try {
            if (offset > recordReader.getPosition()) {
                recordReader.sync(offset);
            }

            this.startFilePosition = recordReader.getPosition();
        }
        catch (IOException e) {
            throw Throwables.propagate(e);
        }

        this.endFilePosition = offset + length;

        Map<String, HivePartitionKey> partitionKeysByName = uniqueIndex(partitionKeys, HivePartitionKey.nameGetter());

        int size = columns.size();

        this.constantBlocks = new Block[size];
        this.hiveColumnIndexes = new int[size];
        this.fieldInspectors = new ObjectInspector[size];

        StructObjectInspector rowInspector = getTableObjectInspector(splitSchema);

        ImmutableList.Builder<String> namesBuilder = ImmutableList.builder();
        ImmutableList.Builder<Type> typesBuilder = ImmutableList.builder();
        ImmutableList.Builder<HiveType> hiveTypesBuilder = ImmutableList.builder();
        for (int columnIndex = 0; columnIndex < columns.size(); columnIndex++) {
            HiveColumnHandle column = columns.get(columnIndex);

            String name = column.getName();
            Type type = typeManager.getType(column.getTypeSignature());

            namesBuilder.add(name);
            typesBuilder.add(type);
            hiveTypesBuilder.add(column.getHiveType());

            hiveColumnIndexes[columnIndex] = column.getHiveColumnIndex();

            if (!column.isPartitionKey()) {
                fieldInspectors[columnIndex] = rowInspector.getStructFieldRef(column.getName()).getFieldObjectInspector();
            }

            if (column.isPartitionKey()) {
                HivePartitionKey partitionKey = partitionKeysByName.get(name);
                checkArgument(partitionKey != null, "No value provided for partition key %s", name);

                byte[] bytes = partitionKey.getValue().getBytes(Charsets.UTF_8);

                BlockBuilder blockBuilder = type.createBlockBuilder(new BlockBuilderStatus());

                if (HiveUtil.isHiveNull(bytes)) {
                    for (int i = 0; i < MAX_PAGE_SIZE; i++) {
                        blockBuilder.appendNull();
                    }
                }
                else if (type.equals(BOOLEAN)) {
                    boolean value;
                    if (isTrue(bytes, 0, bytes.length)) {
                        value = true;
                    }
                    else if (isFalse(bytes, 0, bytes.length)) {
                        value = false;
                    }
                    else {
                        String valueString = new String(bytes, Charsets.UTF_8);
                        throw new IllegalArgumentException(String.format("Invalid partition value '%s' for BOOLEAN partition key %s", valueString, name));
                    }
                    for (int i = 0; i < MAX_PAGE_SIZE; i++) {
                        BOOLEAN.writeBoolean(blockBuilder, value);
                    }
                }
                else if (type.equals(BIGINT)) {
                    if (bytes.length == 0) {
                        throw new IllegalArgumentException(String.format("Invalid partition value '' for BIGINT partition key %s", name));
                    }
                    long value = parseLong(bytes, 0, bytes.length);
                    for (int i = 0; i < MAX_PAGE_SIZE; i++) {
                        BIGINT.writeLong(blockBuilder, value);
                    }
                }
                else if (type.equals(DOUBLE)) {
                    if (bytes.length == 0) {
                        throw new IllegalArgumentException(String.format("Invalid partition value '' for DOUBLE partition key %s", name));
                    }
                    double value = parseDouble(bytes, 0, bytes.length);
                    for (int i = 0; i < MAX_PAGE_SIZE; i++) {
                        DOUBLE.writeDouble(blockBuilder, value);
                    }
                }
                else if (type.equals(VARCHAR)) {
                    Slice value = Slices.wrappedBuffer(bytes);
                    for (int i = 0; i < MAX_PAGE_SIZE; i++) {
                        VARCHAR.writeSlice(blockBuilder, value);
                    }
                }
                else if (type.equals(DATE)) {
                    long value = ISODateTimeFormat.date().withZone(DateTimeZone.UTC).parseMillis(partitionKey.getValue());
                    for (int i = 0; i < MAX_PAGE_SIZE; i++) {
                        DATE.writeLong(blockBuilder, value);
                    }
                }
                else if (TIMESTAMP.equals(type)) {
                    long value = parseHiveTimestamp(partitionKey.getValue(), hiveStorageTimeZone);
                    for (int i = 0; i < MAX_PAGE_SIZE; i++) {
                        DATE.writeLong(blockBuilder, value);
                    }
                }
                else {
                    throw new UnsupportedOperationException("Partition key " + name + " had an unsupported column type " + type);
                }

                constantBlocks[columnIndex] = blockBuilder.build();
            }
            else if (hiveColumnIndexes[columnIndex] >= recordReader.getCurrentKeyBufferObj().getColumnNumber()) {
                // this partition may contain fewer fields than what's declared in the schema
                // this happens when additional columns are added to the hive table after a partition has been created
                BlockBuilder blockBuilder = type.createBlockBuilder(new BlockBuilderStatus());
                for (int i = 0; i < MAX_PAGE_SIZE; i++) {
                    blockBuilder.appendNull();
                }
                constantBlocks[columnIndex] = blockBuilder.build();
            }
        }
        types = typesBuilder.build();
        hiveTypes = hiveTypesBuilder.build();
        columnNames = namesBuilder.build();

        columnBatch = new BytesRefArrayWritable[hiveColumnIndexes.length];
        columnBatchLoaded = new boolean[hiveColumnIndexes.length];
    }

    @Override
    public long getTotalBytes()
    {
        return 0;
    }

    @Override
    public long getCompletedBytes()
    {
        return completedBytes;
    }

    @Override
    public long getReadTimeNanos()
    {
        return 0;
    }

    @Override
    public boolean isFinished()
    {
        return closed;
    }

    @Override
    public Page getNextPage()
    {
        try {
            // advance in the current batch
            pageId++;
            positionInBatch += currentPageSize;

            // if the batch has been consumed, read the next batch
            if (positionInBatch >= currentBatchSize) {
                //noinspection deprecation
                if (!recordReader.nextColumnsBatch() || recordReader.lastSeenSyncPos() >= endFilePosition) {
                    close();
                    return null;
                }

                currentBatchSize = recordReader.getCurrentKeyBufferObj().getNumberRows();
                positionInBatch = 0;

                Arrays.fill(columnBatchLoaded, false);
            }

            currentPageSize = Ints.checkedCast(Math.min(currentBatchSize - positionInBatch, MAX_PAGE_SIZE));

            RcFileColumnsBatch rcFileColumnsBatch = new RcFileColumnsBatch(pageId, positionInBatch);

            Block[] blocks = new Block[hiveColumnIndexes.length];
            for (int fieldId = 0; fieldId < blocks.length; fieldId++) {
                Type type = types.get(fieldId);
                if (constantBlocks[fieldId] != null) {
                    blocks[fieldId] = constantBlocks[fieldId].getRegion(0, currentPageSize);
                }
                else if (type instanceof FixedWidthType) {
                    LazyBlockLoader<LazyFixedWidthBlock> loader = blockLoader.fixedWidthBlockLoader(rcFileColumnsBatch, fieldId, hiveTypes.get(fieldId));
                    blocks[fieldId] = new LazyFixedWidthBlock(((FixedWidthType) type).getFixedSize(), currentPageSize, loader);
                }
                else {
                    LazyBlockLoader<LazySliceArrayBlock> loader = blockLoader.variableWidthBlockLoader(rcFileColumnsBatch,
                            fieldId,
                            hiveTypes.get(fieldId),
                            fieldInspectors[fieldId]);
                    blocks[fieldId] = new LazySliceArrayBlock(currentPageSize, loader);
                }
            }

            Page page = new Page(currentPageSize, blocks);
            completedBytes = recordReader.getPosition() - startFilePosition;

            return page;
        }
        catch (IOException | RuntimeException e) {
            closeWithSuppression(e);
            throw new PrestoException(HIVE_CURSOR_ERROR, e);
        }
    }

    @Override
    public void close()
    {
        // some hive input formats are broken and bad things can happen if you close them multiple times
        if (closed) {
            return;
        }
        closed = true;

        recordReader.close();
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("columnNames", columnNames)
                .add("types", types)
                .toString();
    }

    private void closeWithSuppression(Throwable throwable)
    {
        checkNotNull(throwable, "throwable is null");
        try {
            close();
        }
        catch (RuntimeException e) {
            throwable.addSuppressed(e);
        }
    }

    public final class RcFileColumnsBatch
    {
        private final int expectedBatchId;
        private final int positionInBatch;

        private RcFileColumnsBatch(int expectedBatchId, int positionInBatch)
        {
            this.expectedBatchId = expectedBatchId;
            this.positionInBatch = positionInBatch;
        }

        public BytesRefArrayWritable getColumn(int fieldId)
                throws IOException
        {
            checkState(pageId == expectedBatchId);
            if (!columnBatchLoaded[fieldId]) {
                columnBatch[fieldId] = recordReader.getColumn(hiveColumnIndexes[fieldId], columnBatch[fieldId]);
                columnBatchLoaded[fieldId] = true;
            }
            return columnBatch[fieldId];
        }

        public int getPositionInBatch()
        {
            return positionInBatch;
        }

        @Override
        public String toString()
        {
            return toStringHelper(this)
                    .add("expectedBatchId", expectedBatchId)
                    .add("positionInBatch", positionInBatch)
                    .toString();
        }
    }
}
