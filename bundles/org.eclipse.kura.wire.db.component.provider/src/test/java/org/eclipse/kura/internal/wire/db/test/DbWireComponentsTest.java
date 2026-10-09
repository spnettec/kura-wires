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
 *
 *******************************************************************************/
package org.eclipse.kura.internal.wire.db.test;

import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.kura.KuraException;
import org.eclipse.kura.type.TypedValues;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.osgi.framework.InvalidSyntaxException;

public class DbWireComponentsTest extends DbComponentsTestBase {

    @ParameterizedTest
    @MethodSource("targets")
    public void shouldSupportInteger(WireComponentTestTarget wireComponentTestTarget, StoreTestTarget storeTestTarget)
            throws KuraException, InvalidSyntaxException, InterruptedException, ExecutionException, TimeoutException {
        initialize(wireComponentTestTarget, storeTestTarget);

        givenAnEnvelopeReceivedByStore("foo", TypedValues.newIntegerValue(23));

        whenQueryIsPerformed("SELECT * FROM \"" + tableName + "\";");

        thenFilterEmitsEnvelopeWithProperty("foo", TypedValues.newIntegerValue(23));
    }

    @ParameterizedTest
    @MethodSource("targets")
    public void shouldSupportLong(WireComponentTestTarget wireComponentTestTarget, StoreTestTarget storeTestTarget)
            throws KuraException, InvalidSyntaxException, InterruptedException, ExecutionException, TimeoutException {
        initialize(wireComponentTestTarget, storeTestTarget);

        givenAnEnvelopeReceivedByStore("foo", TypedValues.newLongValue(23));

        whenQueryIsPerformed("SELECT * FROM \"" + tableName + "\";");

        thenFilterEmitsEnvelopeWithProperty("foo", TypedValues.newLongValue(23));
    }

    @ParameterizedTest
    @MethodSource("targets")
    public void shouldSupportBoolean(WireComponentTestTarget wireComponentTestTarget, StoreTestTarget storeTestTarget)
            throws KuraException, InvalidSyntaxException, InterruptedException, ExecutionException, TimeoutException {
        initialize(wireComponentTestTarget, storeTestTarget);

        givenAnEnvelopeReceivedByStore("foo", TypedValues.newBooleanValue(true), "bar",
                TypedValues.newBooleanValue(false));

        whenQueryIsPerformed("SELECT * FROM \"" + tableName + "\";");

        thenFilterEmitsEnvelopeWithProperty("foo", TypedValues.newBooleanValue(true));
        thenFilterEmitsEnvelopeWithProperty("bar", TypedValues.newBooleanValue(false));
    }

    @ParameterizedTest
    @MethodSource("targets")
    public void shouldSupportDouble(WireComponentTestTarget wireComponentTestTarget, StoreTestTarget storeTestTarget)
            throws KuraException, InvalidSyntaxException, InterruptedException, ExecutionException, TimeoutException {
        initialize(wireComponentTestTarget, storeTestTarget);

        givenAnEnvelopeReceivedByStore("foo", TypedValues.newDoubleValue(1234.5d));

        whenQueryIsPerformed("SELECT * FROM \"" + tableName + "\";");

        thenFilterEmitsEnvelopeWithProperty("foo", TypedValues.newDoubleValue(1234.5d));
    }

    @ParameterizedTest
    @MethodSource("targets")
    public void shouldSupportFloat(WireComponentTestTarget wireComponentTestTarget, StoreTestTarget storeTestTarget)
            throws KuraException, InvalidSyntaxException, InterruptedException, ExecutionException, TimeoutException {
        initialize(wireComponentTestTarget, storeTestTarget);

        givenAnEnvelopeReceivedByStore("foo", TypedValues.newFloatValue(1234.5f));

        whenQueryIsPerformed("SELECT * FROM \"" + tableName + "\";");

        thenFilterEmitsEnvelopeWithProperty("foo", TypedValues.newDoubleValue(1234.5d));
    }

    @ParameterizedTest
    @MethodSource("targets")
    public void shouldSupportString(WireComponentTestTarget wireComponentTestTarget, StoreTestTarget storeTestTarget)
            throws KuraException, InvalidSyntaxException, InterruptedException, ExecutionException, TimeoutException {
        initialize(wireComponentTestTarget, storeTestTarget);

        givenAnEnvelopeReceivedByStore("foo", TypedValues.newStringValue("bar"));

        whenQueryIsPerformed("SELECT * FROM \"" + tableName + "\";");

        thenFilterEmitsEnvelopeWithProperty("foo", TypedValues.newStringValue("bar"));
    }

    @ParameterizedTest
    @MethodSource("targets")
    public void shouldSupportByteArray(WireComponentTestTarget wireComponentTestTarget, StoreTestTarget storeTestTarget)
            throws KuraException, InvalidSyntaxException, InterruptedException, ExecutionException, TimeoutException {
        initialize(wireComponentTestTarget, storeTestTarget);

        givenAnEnvelopeReceivedByStore("foo", TypedValues.newByteArrayValue(new byte[] { 1, 2, 3 }));

        whenQueryIsPerformed("SELECT * FROM \"" + tableName + "\";");

        thenFilterEmitsEnvelopeWithByteArrayProperty("foo", new byte[] { 1, 2, 3 });
    }

    @ParameterizedTest
    @MethodSource("targets")
    public void shouldSupportMultipleEnvelopes(WireComponentTestTarget wireComponentTestTarget, StoreTestTarget storeTestTarget)
            throws KuraException, InvalidSyntaxException, InterruptedException, ExecutionException, TimeoutException {
        initialize(wireComponentTestTarget, storeTestTarget);

        givenAnEnvelopeReceivedByStore("foo", TypedValues.newIntegerValue(23));
        givenAnEnvelopeReceivedByStore("foo", TypedValues.newIntegerValue(24));
        givenAnEnvelopeReceivedByStore("foo", TypedValues.newIntegerValue(25));

        whenQueryIsPerformed("SELECT * FROM \"" + tableName + "\";");

        thenFilterEmitsEnvelopeWithProperty(0, "foo", TypedValues.newIntegerValue(23));
        thenFilterEmitsEnvelopeWithProperty(1, "foo", TypedValues.newIntegerValue(24));
        thenFilterEmitsEnvelopeWithProperty(2, "foo", TypedValues.newIntegerValue(25));
    }

    @ParameterizedTest
    @MethodSource("targets")
    public void shouldEmitEmptyEnvelopesByDefault(WireComponentTestTarget wireComponentTestTarget, StoreTestTarget storeTestTarget)
            throws KuraException, InvalidSyntaxException, InterruptedException, ExecutionException, TimeoutException {
        initialize(wireComponentTestTarget, storeTestTarget);

        givenAnEnvelopeReceivedByStore("foo", TypedValues.newIntegerValue(23));

        whenQueryIsPerformed("SELECT * FROM \"" + tableName + "\" LIMIT 0;");

        thenFilterEmitsEmptyEnvelope();
    }

    @ParameterizedTest
    @MethodSource("targets")
    public void shouldNotEmitEmptyEnvelopesIfConfigured(WireComponentTestTarget wireComponentTestTarget, StoreTestTarget storeTestTarget)
            throws KuraException, InvalidSyntaxException, InterruptedException, ExecutionException, TimeoutException {
        initialize(wireComponentTestTarget, storeTestTarget);

        givenFilterWithConfig(this.wireComponentTestTarget.filterEmitOnEmptyResultKey(), false);
        givenAnEnvelopeReceivedByStore("foo", TypedValues.newIntegerValue(23));

        whenQueryIsPerformed("SELECT * FROM \"" + tableName + "\" LIMIT 0;");

        thenFilterEmitNoEnvelope();
    }

    @ParameterizedTest
    @MethodSource("targets")
    public void shouldSupportCacheExpirationInterval(WireComponentTestTarget wireComponentTestTarget, StoreTestTarget storeTestTarget)
            throws KuraException, InvalidSyntaxException, InterruptedException, ExecutionException, TimeoutException {
        initialize(wireComponentTestTarget, storeTestTarget);

        givenFilterWithConfig(this.wireComponentTestTarget.filterCacheExpirationIntervalKey(), 5);
        givenAnEnvelopeReceivedByStore("foo", TypedValues.newIntegerValue(23));
        givenPerformedQuery("SELECT * FROM \"" + tableName + "\" ORDER BY ID ASC;");
        givenAnEnvelopeReceivedByStore("foo", TypedValues.newIntegerValue(24));
        givenPerformedQuery("SELECT * FROM \"" + tableName + "\" ORDER BY ID ASC;");

        whenTimePasses(6, TimeUnit.SECONDS);
        whenQueryIsPerformed("SELECT * FROM \"" + tableName + "\" ORDER BY ID ASC;");

        thenEnvelopeRecordCountIs(0, 1);
        thenEnvelopeRecordCountIs(1, 1);
        thenEnvelopeRecordCountIs(2, 2);
        thenFilterEmitsEnvelopeWithProperty(0, 0, "foo", TypedValues.newIntegerValue(23));
        thenFilterEmitsEnvelopeWithProperty(1, 0, "foo", TypedValues.newIntegerValue(23));
        thenFilterEmitsEnvelopeWithProperty(2, 0, "foo", TypedValues.newIntegerValue(23));
        thenFilterEmitsEnvelopeWithProperty(2, 1, "foo", TypedValues.newIntegerValue(24));
    }

    @ParameterizedTest
    @MethodSource("targets")
    public void shouldSupportMaximumTableSize(WireComponentTestTarget wireComponentTestTarget, StoreTestTarget storeTestTarget)
            throws InterruptedException, ExecutionException, TimeoutException, KuraException, InvalidSyntaxException {
        initialize(wireComponentTestTarget, storeTestTarget);

        givenStoreWithConfig(this.wireComponentTestTarget.storeMaximumSizeKey(), 5);
        givenAnEnvelopeReceivedByStore("foo", TypedValues.newIntegerValue(1));
        givenAnEnvelopeReceivedByStore("foo", TypedValues.newIntegerValue(2));
        givenAnEnvelopeReceivedByStore("foo", TypedValues.newIntegerValue(3));
        givenAnEnvelopeReceivedByStore("foo", TypedValues.newIntegerValue(4));
        givenAnEnvelopeReceivedByStore("foo", TypedValues.newIntegerValue(5));
        givenAnEnvelopeReceivedByStore("foo", TypedValues.newIntegerValue(6));
        givenAnEnvelopeReceivedByStore("foo", TypedValues.newIntegerValue(7));

        whenQueryIsPerformed("SELECT * FROM \"" + tableName + "\" ORDER BY ID DESC;");

        thenEnvelopeRecordCountIs(0, 5);
        thenFilterEmitsEnvelopeWithProperty(0, 0, "foo", TypedValues.newIntegerValue(7));
        thenFilterEmitsEnvelopeWithProperty(0, 1, "foo", TypedValues.newIntegerValue(6));
        thenFilterEmitsEnvelopeWithProperty(0, 2, "foo", TypedValues.newIntegerValue(5));
        thenFilterEmitsEnvelopeWithProperty(0, 3, "foo", TypedValues.newIntegerValue(4));
        thenFilterEmitsEnvelopeWithProperty(0, 4, "foo", TypedValues.newIntegerValue(3));
    }

    @ParameterizedTest
    @MethodSource("targets")
    public void shouldSupportMaximumTableSize1(WireComponentTestTarget wireComponentTestTarget, StoreTestTarget storeTestTarget)
            throws InterruptedException, ExecutionException, TimeoutException, KuraException, InvalidSyntaxException {
        initialize(wireComponentTestTarget, storeTestTarget);

        givenStoreWithConfig(this.wireComponentTestTarget.storeMaximumSizeKey(), 5);
        givenAnEnvelopeReceivedByStore("foo", TypedValues.newIntegerValue(1));
        givenAnEnvelopeReceivedByStore("foo", TypedValues.newIntegerValue(2));
        givenAnEnvelopeReceivedByStore("foo", TypedValues.newIntegerValue(3));
        givenAnEnvelopeReceivedByStore("foo", TypedValues.newIntegerValue(4));
        givenAnEnvelopeReceivedByStore("foo", TypedValues.newIntegerValue(5));
        givenAnEnvelopeReceivedByStore("foo", TypedValues.newIntegerValue(6));
        givenAnEnvelopeReceivedByStore("foo", TypedValues.newIntegerValue(7));
        givenStoreWithConfig(this.wireComponentTestTarget.storeMaximumSizeKey(), 1);
        givenAnEnvelopeReceivedByStore("foo", TypedValues.newIntegerValue(8));

        whenQueryIsPerformed("SELECT * FROM \"" + tableName + "\" ORDER BY ID DESC;");

        thenEnvelopeRecordCountIs(0, 1);
        thenFilterEmitsEnvelopeWithProperty(0, 0, "foo", TypedValues.newIntegerValue(8));
    }

    @ParameterizedTest
    @MethodSource("targets")
    public void shouldSupportCleanupRecordKeep(WireComponentTestTarget wireComponentTestTarget, StoreTestTarget storeTestTarget)
            throws InterruptedException, ExecutionException, TimeoutException, KuraException, InvalidSyntaxException {
        initialize(wireComponentTestTarget, storeTestTarget);

        givenStoreWithConfig(this.wireComponentTestTarget.storeMaximumSizeKey(), 5,
                this.wireComponentTestTarget.storeCleanupRecordsKeepKey(), 2);
        givenAnEnvelopeReceivedByStore("foo", TypedValues.newIntegerValue(1));
        givenAnEnvelopeReceivedByStore("foo", TypedValues.newIntegerValue(2));
        givenAnEnvelopeReceivedByStore("foo", TypedValues.newIntegerValue(3));
        givenAnEnvelopeReceivedByStore("foo", TypedValues.newIntegerValue(4));
        givenAnEnvelopeReceivedByStore("foo", TypedValues.newIntegerValue(5));
        givenAnEnvelopeReceivedByStore("foo", TypedValues.newIntegerValue(6));

        whenQueryIsPerformed("SELECT * FROM \"" + tableName + "\" ORDER BY ID DESC;");

        thenEnvelopeRecordCountIs(0, 2);
        thenFilterEmitsEnvelopeWithProperty(0, 0, "foo", TypedValues.newIntegerValue(6));
        thenFilterEmitsEnvelopeWithProperty(0, 1, "foo", TypedValues.newIntegerValue(5));
    }

    @ParameterizedTest
    @MethodSource("targets")
    public void shouldSupportStoreReconfiguration(WireComponentTestTarget wireComponentTestTarget, StoreTestTarget storeTestTarget)
            throws KuraException, InvalidSyntaxException, InterruptedException, ExecutionException, TimeoutException {
        initialize(wireComponentTestTarget, storeTestTarget);

        givenAnEnvelopeReceivedByStore("foo", TypedValues.newIntegerValue(23));

        whenDatabaseIsReconfigured();
        givenAnEnvelopeReceivedByStore("foo", TypedValues.newIntegerValue(24));
        whenQueryIsPerformed("SELECT * FROM \"" + tableName + "\";");

        thenFilterEmitsEnvelopeWithProperty("foo", TypedValues.newIntegerValue(24));
    }

    @ParameterizedTest
    @MethodSource("targets")
    public void shouldNotResetIdIfTableIsEmpty(WireComponentTestTarget wireComponentTestTarget, StoreTestTarget storeTestTarget)
            throws KuraException, InvalidSyntaxException, InterruptedException, ExecutionException, TimeoutException {
        initialize(wireComponentTestTarget, storeTestTarget);

        givenStoreWithConfig(this.wireComponentTestTarget.storeMaximumSizeKey(), 1);
        givenAnEnvelopeReceivedByStore("foo", TypedValues.newIntegerValue(23));
        givenAnEnvelopeReceivedByStore("foo", TypedValues.newIntegerValue(24));

        whenQueryIsPerformed("SELECT * FROM \"" + tableName + "\";");

        thenEnvelopeRecordCountIs(0, 1);
        thenFilterEmitsEnvelopeWithProperty("ID", TypedValues.newLongValue(2));
    }

    public static Collection<Object[]> targets() {
        return Arrays.asList(new Object[][] {
                { WireComponentTestTarget.WIRE_RECORD_QUERY_AND_WIRE_RECORD_STORE, StoreTestTarget.H2 },
                { WireComponentTestTarget.WIRE_RECORD_QUERY_AND_WIRE_RECORD_STORE, StoreTestTarget.SQLITE },
                { WireComponentTestTarget.DB_FILTER_AND_DB_STORE, StoreTestTarget.H2 } });
    }

}
