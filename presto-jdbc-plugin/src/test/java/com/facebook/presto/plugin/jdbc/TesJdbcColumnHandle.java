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
package com.facebook.presto.plugin.jdbc;

import com.facebook.presto.spi.type.BigintType;
import com.facebook.presto.spi.type.VarcharType;
import io.airlift.json.JsonCodec;
import io.airlift.testing.EquivalenceTester;
import org.testng.annotations.Test;

import static io.airlift.json.JsonCodec.jsonCodec;
import static org.testng.Assert.assertEquals;

public class TesJdbcColumnHandle
{
    private final JdbcColumnHandle columnHandle = new JdbcColumnHandle("connectorId", "columnName", VarcharType.VARCHAR, 0);

    @Test
    public void testJsonRoundTrip()
    {
        JsonCodec<JdbcColumnHandle> codec = jsonCodec(JdbcColumnHandle.class);
        String json = codec.toJson(columnHandle);
        JdbcColumnHandle copy = codec.fromJson(json);
        assertEquals(copy, columnHandle);
    }

    @Test
    public void testEquivalence()
    {
        EquivalenceTester.equivalenceTester()
                .addEquivalentGroup(
                        new JdbcColumnHandle("connectorId", "columnName", VarcharType.VARCHAR, 0),
                        new JdbcColumnHandle("connectorId", "columnName", VarcharType.VARCHAR, 0),
                        new JdbcColumnHandle("connectorId", "columnName", BigintType.BIGINT, 0),
                        new JdbcColumnHandle("connectorId", "columnName", VarcharType.VARCHAR, 1))
                .addEquivalentGroup(
                        new JdbcColumnHandle("connectorIdX", "columnName", VarcharType.VARCHAR, 0),
                        new JdbcColumnHandle("connectorIdX", "columnName", VarcharType.VARCHAR, 0),
                        new JdbcColumnHandle("connectorIdX", "columnName", BigintType.BIGINT, 0),
                        new JdbcColumnHandle("connectorIdX", "columnName", VarcharType.VARCHAR, 1))
                .addEquivalentGroup(
                        new JdbcColumnHandle("connectorId", "columnNameX", VarcharType.VARCHAR, 0),
                        new JdbcColumnHandle("connectorId", "columnNameX", VarcharType.VARCHAR, 0),
                        new JdbcColumnHandle("connectorId", "columnNameX", BigintType.BIGINT, 0),
                        new JdbcColumnHandle("connectorId", "columnNameX", VarcharType.VARCHAR, 1))
                .check();
    }
}
