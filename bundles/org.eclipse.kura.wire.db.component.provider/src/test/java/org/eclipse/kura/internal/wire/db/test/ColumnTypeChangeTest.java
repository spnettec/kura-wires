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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.eclipse.kura.KuraException;
import org.eclipse.kura.type.TypedValue;
import org.eclipse.kura.type.TypedValues;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.osgi.framework.InvalidSyntaxException;

public class ColumnTypeChangeTest extends DbComponentsTestBase {

    @ParameterizedTest
    @MethodSource("parameters")
    public void shoudSupportChangingColumnType(WireComponentTestTarget wireComponentTestTarget, StoreTestTarget storeTestTarget, TypedValue<?> initialValue, TypedValue<?> valueWithDifferentType)
            throws KuraException, InvalidSyntaxException, InterruptedException, ExecutionException, TimeoutException {
        initialize(wireComponentTestTarget, storeTestTarget);
        this.initialValue = initialValue;
        this.valueWithDifferentType = valueWithDifferentType;

        givenAnEnvelopeReceivedByStore("foo", initialValue);
        givenAnEnvelopeReceivedByStore("foo", valueWithDifferentType);

        whenQueryIsPerformed("SELECT * FROM \"" + tableName + "\" ORDER BY ID ASC;");

        thenEmittedRecordCountIs(2);
        thenFilterEmitsEnvelopeWithoutProperty(0, 0, "foo");
        thenFilterEmitsEnvelopeWithProperty(0, 1, "foo", valueWithDifferentType);
    }

    private TypedValue<?> initialValue;
    private TypedValue<?> valueWithDifferentType;

    public static List<Object[]> parameters() {
        final List<WireComponentTestTarget> wireComponentTestTargets = Arrays
                .asList(WireComponentTestTarget.WIRE_RECORD_QUERY_AND_WIRE_RECORD_STORE);
        final List<StoreTestTarget> storeTestTargets = Arrays.asList(StoreTestTarget.SQLITE, StoreTestTarget.H2);
        final List<TypedValue<?>> testValues = Arrays.asList( //
                TypedValues.newBooleanValue(true), //
                TypedValues.newIntegerValue(12), //
                TypedValues.newLongValue(12345), //
                TypedValues.newDoubleValue(1234.567d), //
                TypedValues.newStringValue("foo"), //
                TypedValues.newByteArrayValue(new byte[] { 1, 2, 3 }) //
        );

        final List<Object[]> result = new ArrayList<>();

        for (final WireComponentTestTarget wt : wireComponentTestTargets) {
            for (final StoreTestTarget st : storeTestTargets) {
                for (final TypedValue<?> initial : testValues) {
                    for (final TypedValue<?> changed : testValues) {
                        if (initial != changed) {
                            result.add(new Object[] { wt, st, initial, changed });
                        }
                    }
                }
            }
        }

        return result;

    }

}
