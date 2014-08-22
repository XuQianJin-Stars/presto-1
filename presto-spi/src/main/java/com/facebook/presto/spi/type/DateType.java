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
package com.facebook.presto.spi.type;

import com.facebook.presto.spi.ConnectorSession;
import com.facebook.presto.spi.block.Block;
import com.facebook.presto.spi.block.BlockBuilder;

import static com.facebook.presto.spi.type.TimeZoneIndex.getTimeZoneForKey;
import static io.airlift.slice.SizeOf.SIZE_OF_LONG;

//
// A date is stored as milliseconds from 1970-01-01T00:00:00 UTC at midnight on the date.
//
// Note: when dealing with a java.sql.Date it is important to remember that the value is stored
// as the number of milliseconds from 1970-01-01T00:00:00 in UTC but time must be midnight in
// the local time zone.  This mean when converting between a java.sql.Date and this
// type, the time zone offset must be added or removed to keep the time at midnight.
//
public final class DateType
        extends AbstractFixedWidthType
{
    public static final DateType DATE = new DateType();

    public static DateType getInstance()
    {
        return DATE;
    }

    private DateType()
    {
        super("date", long.class, SIZE_OF_LONG);
    }

    @Override
    public boolean isComparable()
    {
        return true;
    }

    @Override
    public boolean isOrderable()
    {
        return true;
    }

    @Override
    public Object getObjectValue(ConnectorSession session, Block block, int position)
    {
        if (block.isNull(position)) {
            return null;
        }

        // convert date to timestamp at midnight in local time zone
        long date = block.getLong(position, 0);
        return new SqlDate(date - getTimeZoneForKey(session.getTimeZoneKey()).getOffset(date), session.getTimeZoneKey());
    }

    @Override
    public boolean equalTo(Block leftBlock, int leftPosition, Block rightBlock, int rightPosition)
    {
        long leftValue = leftBlock.getLong(leftPosition, 0);
        long rightValue = rightBlock.getLong(rightPosition, 0);
        return leftValue == rightValue;
    }

    @Override
    public int hash(Block block, int position)
    {
        long value = block.getLong(position, 0);
        return (int) (value ^ (value >>> 32));
    }

    @Override
    public int compareTo(Block leftBlock, int leftPosition, Block rightBlock, int rightPosition)
    {
        long leftValue = leftBlock.getLong(leftPosition, 0);
        long rightValue = rightBlock.getLong(rightPosition, 0);
        return Long.compare(leftValue, rightValue);
    }

    @Override
    public void appendTo(Block block, int position, BlockBuilder blockBuilder)
    {
        if (block.isNull(position)) {
            blockBuilder.appendNull();
        }
        else {
            blockBuilder.writeLong(block.getLong(position, 0)).closeEntry();
        }
    }

    @Override
    public long getLong(Block block, int position)
    {
        return block.getLong(position, 0);
    }

    @Override
    public void writeLong(BlockBuilder blockBuilder, long value)
    {
        blockBuilder.writeLong(value).closeEntry();
    }
}
