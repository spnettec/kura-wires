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

import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.eclipse.kura.KuraException;
import org.eclipse.kura.type.TypedValues;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.osgi.framework.InvalidSyntaxException;

public class UnsuppotredTypeTest extends DbComponentsTestBase {

    @ParameterizedTest
    @MethodSource("targets")
    public void shouldNotEmitPropertyOfUnsupportedType(WireComponentTestTarget wireComponentTestTarget, StoreTestTarget storeTestTarget)
            throws KuraException, InvalidSyntaxException, InterruptedException, ExecutionException, TimeoutException {
        initialize(wireComponentTestTarget, storeTestTarget);

        // MEDIAN returns BigDecimal, which this fork intentionally supports.
        whenQueryIsPerformed("SELECT TIMESTAMP '2023-01-02 03:04:05' AS OUT;");

        thenEmittedEnvelopeIsEmpty();
    }

    @ParameterizedTest
    @MethodSource("targets")
    public void shouldEmitPropertyAfterManualCastFromUnsupportedType(WireComponentTestTarget wireComponentTestTarget, StoreTestTarget storeTestTarget)
            throws KuraException, InvalidSyntaxException, InterruptedException, ExecutionException, TimeoutException {
        initialize(wireComponentTestTarget, storeTestTarget);

        whenQueryIsPerformed("SELECT CAST(TIMESTAMP '2023-01-02 03:04:05' AS VARCHAR) AS OUT;");

        thenFilterEmitsEnvelopeWithProperty("OUT", TypedValues.newStringValue("2023-01-02 03:04:05"));
    }

    @ParameterizedTest
    @MethodSource("targets")
    public void shouldPreserveLocalBigDecimalConversion(WireComponentTestTarget wireComponentTestTarget,
            StoreTestTarget storeTestTarget) {
        initialize(wireComponentTestTarget, storeTestTarget);
        givenAColumnWithData("test", 1, 2, 3, 4, 5, 6);

        whenQueryIsPerformed("SELECT MEDIAN(\"test\") AS OUT FROM \"" + tableName + "\";");

        thenFilterEmitsEnvelopeWithProperty("OUT", TypedValues.newDoubleValue(3.5));
    }

    public static Collection<Object[]> targets() {
        return Arrays.asList(new Object[][] {
                { WireComponentTestTarget.WIRE_RECORD_QUERY_AND_WIRE_RECORD_STORE, StoreTestTarget.H2 },
                { WireComponentTestTarget.DB_FILTER_AND_DB_STORE, StoreTestTarget.H2 } });
    }

}
