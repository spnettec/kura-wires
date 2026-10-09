/*******************************************************************************
 * Copyright (c) 2024, 2025 Eurotech and/or its affiliates and others
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

import static org.eclipse.kura.type.TypedValues.newTypedValue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.eclipse.kura.KuraException;
import org.eclipse.kura.type.TypedValue;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.osgi.framework.InvalidSyntaxException;

public class MultipleValueStoreTest extends DbComponentsTestBase {

    @ParameterizedTest
    @MethodSource("parameters")
    public void shouldSupportStoringMultipleValuesOfSameType(WireComponentTestTarget wireComponentTestTarget, StoreTestTarget storeTestTarget, TypedValue<?> insertedValue, TypedValue<?> extractedValue)
            throws KuraException, InvalidSyntaxException, InterruptedException, ExecutionException, TimeoutException {
        initialize(wireComponentTestTarget, storeTestTarget);
        this.insertedValue = insertedValue;
        this.expectedValue = extractedValue;

        givenAnEnvelopeReceivedByStore("foo", insertedValue);
        givenAnEnvelopeReceivedByStore("foo", insertedValue);
        givenAnEnvelopeReceivedByStore("foo", insertedValue);

        whenQueryIsPerformed("SELECT * FROM \"" + tableName + "\" ORDER BY ID ASC;");

        thenEmittedRecordCountIs(3);
        thenFilterEmitsEnvelopeWithProperty(0, 0, "foo", expectedValue);
        thenFilterEmitsEnvelopeWithProperty(0, 1, "foo", expectedValue);
        thenFilterEmitsEnvelopeWithProperty(0, 2, "foo", expectedValue);
    }

    private TypedValue<?> insertedValue;
    private TypedValue<?> expectedValue;

    public static List<Object[]> parameters() {

        final Boolean testBoolean = true;
        final Integer testInteger = 12;
        final Long testLong = 1235L;
        final Double testDouble = 1d;
        final Float testFloat = 1f;
        final String testString = "foo";
        final byte[] testBa = new byte[] { 1, 2, 3 };

        final List<WireComponentTestTarget> wireComponentTestTargets = Arrays
                .asList(WireComponentTestTarget.WIRE_RECORD_QUERY_AND_WIRE_RECORD_STORE);

        final List<StoreTestTarget> storeTestTargets = Arrays.asList(StoreTestTarget.SQLITE, StoreTestTarget.H2);

        final List<Object> insertedValues = Arrays.asList( //
                testBoolean, //
                testInteger, //
                testLong, //
                testDouble, //
                testFloat, //
                testString, //
                testBa //
        );

        final List<Object> expectedValues = Arrays.asList( //
                testBoolean, //
                testInteger, //
                testLong, //
                testDouble, //
                testDouble, // floats are returned as doubles
                testString, //
                testBa //
        );

        final List<Object[]> result = new ArrayList<>();

        for (final WireComponentTestTarget wt : wireComponentTestTargets) {
            for (final StoreTestTarget st : storeTestTargets) {

                final Iterator<Object> insertedIter = insertedValues.iterator();
                final Iterator<Object> expectedIter = expectedValues.iterator();

                while (insertedIter.hasNext()) {
                    result.add(new Object[] { wt, st, newTypedValue(insertedIter.next()),
                            newTypedValue(expectedIter.next()) });
                }
            }
        }

        return result;

    }

}
