/*******************************************************************************
 * Copyright (c) 2023 Eurotech and/or its affiliates and others
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *  Eurotech
 *******************************************************************************/
package org.eclipse.kura.internal.wire.db.test;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import org.eclipse.kura.type.TypedValues;
import org.eclipse.kura.wire.WireRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Functional scenarios from the upstream DS fixture; service registration is not simulated. */
class DbWireRecordStoreLifecycleTest extends DbComponentsTestBase {

    @BeforeEach
    void start() {
        initialize(WireComponentTestTarget.DB_FILTER_AND_DB_STORE, StoreTestTarget.H2);
        givenStoreWithConfig("maximum.table.size", 1200, "cleanup.records.keep", 1100);
    }

    @Test
    void testReceive() throws Exception {
        WireRecord first = new WireRecord(Map.of("key", TypedValues.newStringValue("val")));
        long before = System.currentTimeMillis();
        givenRecordsReceivedByStore(List.of(first));
        try (Connection connection = openConnection()) {
            try (var tables = connection.getMetaData().getTables(null, null, this.tableName, null)) {
                assertTrue(tables.next());
                assertEquals(this.tableName, tables.getString("TABLE_NAME"));
                assertFalse(tables.next());
            }
            assertEquals(1, queryLong(connection, "SELECT COUNT(*) FROM " + this.tableName));
            try (var statement = connection.createStatement();
                    var rows = statement.executeQuery("SELECT * FROM " + this.tableName)) {
                assertTrue(rows.next());
                assertEquals("val", rows.getString("key"));
                long timestamp = rows.getLong("TIMESTAMP");
                assertTrue(timestamp >= before && timestamp <= System.currentTimeMillis());
            }
            givenRecordsReceivedByStore(List.of(first));
            WireRecord bytes = new WireRecord(Map.of("blobkey",
                    TypedValues.newByteArrayValue("val".getBytes(StandardCharsets.UTF_8))));
            WireRecord scalars = new WireRecord(Map.of(
                    "boolkey", TypedValues.newBooleanValue(true),
                    "dblkey", TypedValues.newDoubleValue(1.234),
                    "intkey", TypedValues.newIntegerValue(1234),
                    "longkey", TypedValues.newLongValue(1234L),
                    "floatkey", TypedValues.newFloatValue(123.2f)));
            givenRecordsReceivedByStore(List.of(first, bytes, scalars));
            assertEquals(5, queryLong(connection, "SELECT COUNT(*) FROM " + this.tableName));
        }
    }

    @Test
    void testCleanupSequence() throws Exception {
        for (int i = 0; i < 1200; i++) {
            givenAnEnvelopeReceivedByStore("key", TypedValues.newStringValue("val"));
        }
        try (Connection connection = openConnection()) {
            assertEquals(1200, queryLong(connection, "SELECT COUNT(*) FROM " + this.tableName));
            long oldId = queryLong(connection, "SELECT MIN(ID) FROM " + this.tableName);
            for (int i = 0; i < 5; i++) {
                givenAnEnvelopeReceivedByStore("key", TypedValues.newStringValue("val"));
            }
            assertEquals(1104, queryLong(connection, "SELECT COUNT(*) FROM " + this.tableName));
            assertTrue(queryLong(connection, "SELECT MIN(ID) FROM " + this.tableName) > oldId);
        }
    }

    private static long queryLong(Connection connection, String query) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(query)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }
}
