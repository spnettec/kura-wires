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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.eclipse.kura.core.testutil.TestUtil;
import org.eclipse.kura.crypto.CryptoService;
import org.eclipse.kura.db.BaseDbService;
import org.eclipse.kura.internal.db.h2db.provider.H2DbServiceImpl;
import org.eclipse.kura.internal.db.sqlite.provider.SqliteDbServiceImpl;
import org.eclipse.kura.internal.db.sqlite.provider.SqliteDebugShell;
import org.eclipse.kura.internal.wire.db.filter.DbWireRecordFilter;
import org.eclipse.kura.internal.wire.db.store.DbWireRecordStore;
import org.eclipse.kura.internal.wire.query.WireRecordQueryComponent;
import org.eclipse.kura.internal.wire.store.WireRecordStoreComponent;
import org.eclipse.kura.type.TypedValue;
import org.eclipse.kura.type.TypedValues;
import org.eclipse.kura.wire.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.osgi.service.component.ComponentContext;

/** Real isolated database/component fixture; no OSGi graph or service-registry emulation. */
public class DbComponentsTestBase {

    protected String tableName;
    protected String dbServicePid;
    protected WireComponentTestTarget wireComponentTestTarget;
    protected StoreTestTarget storeTestTarget;

    @TempDir
    Path directory;

    private Object database;
    private Object store;
    private Object filter;
    private final Map<String, Object> storeProperties = new HashMap<>();
    private final Map<String, Object> filterProperties = new HashMap<>();
    private final List<WireEnvelope> receivedEnvelopes = new ArrayList<>();
    private final List<ExecutorService> databaseExecutors = new ArrayList<>();
    private final ComponentContext context = mock(ComponentContext.class);

    protected void initialize(WireComponentTestTarget wireTarget, StoreTestTarget storeTarget) {
        this.wireComponentTestTarget = wireTarget;
        this.storeTestTarget = storeTarget;
        this.tableName = "TEST_TABLE";
        this.dbServicePid = "testDb_" + UUID.randomUUID();
        try {
            CryptoService crypto = mock(CryptoService.class);
            when(crypto.decryptAes(any(char[].class))).thenReturn(new char[0]);
            if (storeTarget == StoreTestTarget.H2) {
                H2DbServiceImpl h2 = new H2DbServiceImpl();
                h2.setCryptoService(crypto);
                this.database = h2;
            } else {
                SqliteDbServiceImpl sqlite = new SqliteDbServiceImpl();
                sqlite.setCryptoService(crypto);
                sqlite.setDebugShell(mock(SqliteDebugShell.class));
                this.database = sqlite;
            }
            call(this.database, "activate", databaseConfig());
            if (storeTarget == StoreTestTarget.H2) {
                this.databaseExecutors.add((ExecutorService) TestUtil.getFieldValue(this.database, "executor"));
                this.databaseExecutors.add((ExecutorService) TestUtil.getFieldValue(this.database, "executorService"));
            }
            try (var connection = ((BaseDbService) this.database).getConnection()) {
                assertFalse(connection.isClosed(), "Fixture database must be available before activating components");
            }
            WireHelperService helper = mock(WireHelperService.class);
            WireSupport filterSupport = mock(WireSupport.class);
            doAnswer(invocation -> {
                List<WireRecord> records = invocation.getArgument(0);
                this.receivedEnvelopes.add(new WireEnvelope("query", records));
                return null;
            }).when(filterSupport).emit(any());
            if (wireTarget == WireComponentTestTarget.DB_FILTER_AND_DB_STORE) {
                this.store = new DbWireRecordStore();
                this.filter = new DbWireRecordFilter();
                call(this.store, "bindDbService", this.database);
                call(this.filter, "bindDbService", this.database);
            } else {
                this.store = new WireRecordStoreComponent();
                this.filter = new WireRecordQueryComponent();
                call(this.store, "bindWireRecordStoreProvider", this.database);
                call(this.filter, "bindQueryableWireRecordStoreProvider", this.database);
            }
            when(helper.newWireSupport(eq((WireComponent) this.store), any())).thenReturn(mock(WireSupport.class));
            when(helper.newWireSupport(eq((WireComponent) this.filter), any())).thenReturn(filterSupport);
            call(this.store, "bindWireHelperService", helper);
            call(this.filter, "bindWireHelperService", helper);
            this.storeProperties.put(wireTarget.storeNameKey(), this.tableName);
            this.filterProperties.put(wireTarget.filterQueryPropertyKey(), "SELECT 1");
            this.filterProperties.put(wireTarget.filterCacheExpirationIntervalKey(), 0);
            this.filterProperties.put(wireTarget.filterEmitOnEmptyResultKey(), true);
            call(this.store, "activate", this.context, this.storeProperties);
            call(this.filter, "activate", this.context, this.filterProperties);
        } catch (Exception e) {
            throw new AssertionError("Database component fixture could not start", e);
        }
    }

    private Map<String, Object> databaseConfig() {
        String name = this.storeTestTarget == StoreTestTarget.H2 ? UUID.randomUUID().toString()
                : this.directory.resolve(UUID.randomUUID() + ".db").toString();
        Map<String, Object> config = new HashMap<>(this.storeTestTarget.getConfigurationForDatabase(name));
        config.put("kura.service.pid", this.dbServicePid);
        config.put("db.defrag.enabled", false);
        config.put("db.wal.checkpoint.enabled", false);
        return config;
    }

    @AfterEach
    void cleanUp() throws Exception {
        try {
            if (this.store != null && this.filter != null) {
                if (this.wireComponentTestTarget == WireComponentTestTarget.DB_FILTER_AND_DB_STORE) {
                    call(this.filter, "deactivate", this.context);
                    call(this.store, "deactivate", this.context);
                } else {
                    call(this.filter, "deactivate");
                    call(this.store, "deactivate");
                    call(this.store, "unbindWireRecordStoreProvider", this.database);
                    call(this.filter, "unbindQueryableWireRecordStoreProvider", this.database);
                }
            }
        } finally {
            if (this.database != null) {
                call(this.database, "deactivate");
            }
            for (ExecutorService executor : this.databaseExecutors) {
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Database executor must terminate");
            }
        }
    }

    protected void givenAColumnWithData(String name, Object... data) {
        for (Object value : data) {
            givenAnEnvelopeReceivedByStore(name, TypedValues.newTypedValue(value));
        }
    }

    protected void givenAnEnvelopeReceivedByStore(Object... args) {
        ((WireReceiver) this.store).onWireReceive(new WireEnvelope("emitter",
                List.of(new WireRecord(collectArgsToMap(args, TypedValue.class::cast)))));
    }

    protected void givenRecordsReceivedByStore(List<WireRecord> records) {
        ((WireReceiver) this.store).onWireReceive(new WireEnvelope("emitter", records));
    }

    protected java.sql.Connection openConnection() throws java.sql.SQLException {
        return ((BaseDbService) this.database).getConnection();
    }

    protected void givenStoreWithConfig(Object... args) {
        this.storeProperties.putAll(collectArgsToMap(args, Function.identity()));
        call(this.store, "updated", this.storeProperties);
    }

    protected void givenFilterWithConfig(Object... args) {
        this.filterProperties.putAll(collectArgsToMap(args, Function.identity()));
        call(this.filter, "updated", this.filterProperties);
    }

    protected void whenDatabaseIsReconfigured() {
        call(this.database, "updated", databaseConfig());
        try (var connection = ((BaseDbService) this.database).getConnection()) {
            assertFalse(connection.isClosed());
        } catch (Exception e) {
            throw new AssertionError("Reconfigured database unavailable", e);
        }
    }

    protected void givenPerformedQuery(String sql) {
        whenQueryIsPerformed(sql);
    }

    protected void whenQueryIsPerformed(String sql) {
        String key = this.wireComponentTestTarget.filterQueryPropertyKey();
        // Match ConfigurationAdmin.updateIfDifferent: do not reset caches for unchanged options.
        if (!sql.equals(this.filterProperties.get(key))) {
            givenFilterWithConfig(key, sql);
        }
        ((WireReceiver) this.filter).onWireReceive(new WireEnvelope("emitter", List.of()));
    }

    protected void whenTimePasses(long amount, TimeUnit unit) {
        // Age the existing cache rather than waiting six seconds for each database target.
        try {
            if (this.filter instanceof DbWireRecordFilter) {
                Calendar refreshed = (Calendar) TestUtil.getFieldValue(this.filter, "lastRefreshedTime");
                refreshed.setTimeInMillis(refreshed.getTimeInMillis() - unit.toMillis(amount));
            } else {
                Object state = TestUtil.getFieldValue(this.filter, "state");
                Optional<?> cached = (Optional<?>) TestUtil.getFieldValue(state, "cachedRecords");
                assertTrue(cached.isPresent(), "Expiry scenario must have cached a query result");
                long timestamp = (long) TestUtil.getFieldValue(cached.get(), "timestamp");
                TestUtil.setFieldValue(cached.get(), "timestamp", timestamp - unit.toNanos(amount));
            }
        } catch (Exception e) {
            throw new AssertionError("Could not age the query cache", e);
        }
    }

    private static void call(Object target, String method, Object... arguments) {
        try {
            for (Class<?> type = target.getClass(); type != Object.class; type = type.getSuperclass()) {
                for (var candidate : type.getDeclaredMethods()) {
                    if (!candidate.getName().equals(method) || candidate.getParameterCount() != arguments.length) {
                        continue;
                    }
                    boolean matches = true;
                    for (int i = 0; i < arguments.length; i++) {
                        matches &= arguments[i] == null || candidate.getParameterTypes()[i].isInstance(arguments[i]);
                    }
                    if (matches) {
                        candidate.setAccessible(true);
                        candidate.invoke(target, arguments);
                        return;
                    }
                }
            }
            throw new NoSuchMethodException(method);
        } catch (Throwable e) {
            throw new AssertionError("Fixture lifecycle call failed: " + method, e);
        }
    }

    protected void thenEmittedEnvelopeIsEmpty() {
        final WireEnvelope envelope = this.receivedEnvelopes.get(0);

        if (envelope.getRecords().isEmpty()) {
            return;
        }

        final WireRecord record = envelope.getRecords().get(0);

        assertTrue(record.getProperties().isEmpty());
    }

    protected void thenEmittedRecordCountIs(final int expectedValue) {
        assertEquals(expectedValue, this.receivedEnvelopes.get(0).getRecords().size());
    }

    protected void thenFilterEmitsEnvelopeWithProperty(final String key, final TypedValue<?> value) {
        thenFilterEmitsEnvelopeWithProperty(0, 0, key, value);
    }

    protected void thenFilterEmitsEnvelopeWithProperty(final int recordIndex, final String key,
            final TypedValue<?> value) {
        thenFilterEmitsEnvelopeWithProperty(0, recordIndex, key, value);
    }

    protected void thenFilterEmitsEnvelopeWithProperty(final int envelopeIndex, final int recordIndex, final String key,
            final TypedValue<?> value) {
        final WireEnvelope envelope = this.receivedEnvelopes.get(envelopeIndex);

        final WireRecord record = envelope.getRecords().get(recordIndex);

        assertEquals(value, record.getProperties().get(key));
    }

    protected void thenFilterEmitsEnvelopeWithoutProperty(final int envelopeIndex, final int recordIndex,
            final String key) {
        final WireEnvelope envelope = this.receivedEnvelopes.get(envelopeIndex);

        final WireRecord record = envelope.getRecords().get(recordIndex);

        if (record.getProperties().containsKey(key)) {
            fail("record contains property \"" + key + "\" with value: " + record.getProperties().get(key)
                    + " envelope records: "
                    + envelope.getRecords().stream().map(WireRecord::getProperties).collect(Collectors.toList()));
        }
    }

    protected void thenFilterEmitsEnvelopeWithByteArrayProperty(final String key, final byte[] value) {
        thenFilterEmitsEnvelopeWithByteArrayProperty(0, 0, key, value);
    }

    protected void thenFilterEmitsEnvelopeWithByteArrayProperty(final int envelopeIndex, final int recordIndex,
            final String key, final byte[] value) {
        final WireEnvelope envelope = this.receivedEnvelopes.get(envelopeIndex);

        final WireRecord record = envelope.getRecords().get(recordIndex);

        assertArrayEquals(value, (byte[]) record.getProperties().get(key).getValue());
    }

    protected void thenFilterEmitsEmptyEnvelope() {
        final WireEnvelope envelope = this.receivedEnvelopes.get(0);

        assertEquals(0, envelope.getRecords().size());
    }

    protected void thenFilterEmitNoEnvelope() {
        assertEquals(0, this.receivedEnvelopes.size());
    }

    protected void thenEnvelopeRecordCountIs(final int envelopeIndex, final int recordCount) {
        assertEquals(recordCount, this.receivedEnvelopes.get(envelopeIndex).getRecords().size());
    }

    protected <T> Map<String, T> collectArgsToMap(final Object[] args, final Function<Object, T> valueMapper) {
        final Iterator<Object> iter = Arrays.asList(args).iterator();
        final Map<String, T> properties = new HashMap<>();

        while (iter.hasNext()) {
            final String key = (String) iter.next();
            final T value = valueMapper.apply(iter.next());

            properties.put(key, value);
        }

        return properties;
    }
}
