/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iceberg.mr.hive;

import java.io.IOException;
import java.util.List;
import org.apache.iceberg.AssertHelpers;
import org.apache.iceberg.HistoryEntry;
import org.apache.iceberg.Table;
import org.junit.Assert;
import org.junit.Test;

import static org.apache.iceberg.mr.hive.HiveIcebergTestUtils.timestampAfterSnapshot;

/**
 * Tests covering the time travel feature, aka reading from a table as of a certain snapshot.
 */
public class TestHiveIcebergTimeTravel extends HiveIcebergStorageHandlerWithEngineBase {

  @Test
  public void testSelectAsOfTimestamp() throws IOException, InterruptedException {
    Table table = testTables.createTableWithVersions(shell, "customers",
        HiveIcebergStorageHandlerTestUtils.CUSTOMER_SCHEMA,
        fileFormat, HiveIcebergStorageHandlerTestUtils.CUSTOMER_RECORDS, 2);

    List<Object[]> rows = shell.executeStatement(
        "SELECT * FROM customers FOR SYSTEM_TIME AS OF '" + timestampAfterSnapshot(table, 0) + "'");

    Assert.assertEquals(3, rows.size());

    rows = shell.executeStatement(
        "SELECT * FROM customers FOR SYSTEM_TIME AS OF '" + timestampAfterSnapshot(table, 1) + "'");

    Assert.assertEquals(4, rows.size());

    AssertHelpers.assertThrows("should throw exception", IllegalArgumentException.class,
        "java.lang.IllegalArgumentException: Cannot find a snapshot older than 1970-01-01", () -> {
        shell.executeStatement("SELECT * FROM customers FOR SYSTEM_TIME AS OF '1970-01-01 00:00:00'");
        });
  }

  @Test
  public void testSelectAsOfVersion() throws IOException, InterruptedException {
    Table table = testTables.createTableWithVersions(shell, "customers",
        HiveIcebergStorageHandlerTestUtils.CUSTOMER_SCHEMA,
        fileFormat, HiveIcebergStorageHandlerTestUtils.CUSTOMER_RECORDS, 2);

    HistoryEntry first = table.history().get(0);
    List<Object[]> rows =
        shell.executeStatement("SELECT * FROM customers FOR SYSTEM_VERSION AS OF " + first.snapshotId());

    Assert.assertEquals(3, rows.size());

    HistoryEntry second = table.history().get(1);
    rows = shell.executeStatement("SELECT * FROM customers FOR SYSTEM_VERSION AS OF " + second.snapshotId());

    Assert.assertEquals(4, rows.size());

    AssertHelpers.assertThrows("should throw exception", IllegalArgumentException.class,
        "Cannot find snapshot with ID 1234", () -> {
          shell.executeStatement("SELECT * FROM customers FOR SYSTEM_VERSION AS OF 1234");
        });
  }

  @Test
  public void testCTASAsOfVersionAndTimestamp() throws IOException, InterruptedException {
    Table table = testTables.createTableWithVersions(shell, "customers",
        HiveIcebergStorageHandlerTestUtils.CUSTOMER_SCHEMA, fileFormat,
        HiveIcebergStorageHandlerTestUtils.CUSTOMER_RECORDS, 3);

    shell.executeStatement("CREATE TABLE customers2 AS SELECT * FROM customers FOR SYSTEM_VERSION AS OF " +
        table.history().get(0).snapshotId());

    List<Object[]> rows = shell.executeStatement("SELECT * FROM customers2");
    Assert.assertEquals(3, rows.size());

    shell.executeStatement("INSERT INTO customers2 SELECT * FROM customers FOR SYSTEM_VERSION AS OF " +
        table.history().get(1).snapshotId());

    rows = shell.executeStatement("SELECT * FROM customers2");
    Assert.assertEquals(7, rows.size());

    shell.executeStatement("CREATE TABLE customers3 AS SELECT * FROM customers FOR SYSTEM_TIME AS OF '" +
        timestampAfterSnapshot(table, 1) + "'");

    rows = shell.executeStatement("SELECT * FROM customers3");
    Assert.assertEquals(4, rows.size());

    shell.executeStatement("INSERT INTO customers3 SELECT * FROM customers FOR SYSTEM_TIME AS OF '" +
        timestampAfterSnapshot(table, 0) + "'");

    rows = shell.executeStatement("SELECT * FROM customers3");
    Assert.assertEquals(7, rows.size());
  }

  @Test
  public void testAsOfWithJoins() throws IOException, InterruptedException {
    Table table = testTables.createTableWithVersions(shell, "customers",
        HiveIcebergStorageHandlerTestUtils.CUSTOMER_SCHEMA, fileFormat,
        HiveIcebergStorageHandlerTestUtils.CUSTOMER_RECORDS, 4);

    List<Object[]> rows = shell.executeStatement("SELECT * FROM " +
        "customers FOR SYSTEM_TIME AS OF '" + timestampAfterSnapshot(table, 0) + "' fv, " +
        "customers FOR SYSTEM_TIME AS OF '" + timestampAfterSnapshot(table, 1) + "' sv " +
        "WHERE fv.first_name=sv.first_name");

    Assert.assertEquals(4, rows.size());

    rows = shell.executeStatement("SELECT * FROM " +
        "customers FOR SYSTEM_TIME AS OF '" + timestampAfterSnapshot(table, 1) + "' sv, " +
        "customers FOR SYSTEM_TIME AS OF '" + timestampAfterSnapshot(table, 2) + "' tv " +
        "WHERE sv.first_name=tv.first_name");

    Assert.assertEquals(8, rows.size());

    rows = shell.executeStatement("SELECT * FROM " +
        "customers FOR SYSTEM_TIME AS OF '" + timestampAfterSnapshot(table, 2) + "' sv, " +
        "customers lv " +
        "WHERE sv.first_name=lv.first_name");

    Assert.assertEquals(14, rows.size());

    rows = shell.executeStatement("SELECT * FROM " +
        "customers FOR SYSTEM_TIME AS OF '" + timestampAfterSnapshot(table, 1) + "' sv, " +
        "customers FOR SYSTEM_VERSION AS OF " + table.history().get(2).snapshotId() + " tv " +
        "WHERE sv.first_name=tv.first_name");

    Assert.assertEquals(8, rows.size());
  }
}
