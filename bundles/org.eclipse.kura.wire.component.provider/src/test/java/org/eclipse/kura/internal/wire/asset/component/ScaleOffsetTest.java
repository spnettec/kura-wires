/*******************************************************************************
 * Copyright (c) 2024 Eurotech and/or its affiliates and others
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
package org.eclipse.kura.internal.wire.asset.component;

import java.util.Optional;

import org.eclipse.kura.channel.ScaleOffsetType;
import org.eclipse.kura.type.DataType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

public class ScaleOffsetTest extends WireAssetTestBase {

    @ParameterizedTest
    @EnumSource(TriggerMode.class)
    public void shouldSupportMissingScaleOffset(TriggerMode triggerMode) {
        this.triggerMode = triggerMode;
        givenAssetChannel("foo", DataType.DOUBLE, ScaleOffsetType.DEFINED_BY_VALUE_TYPE, Optional.empty(),
                Optional.empty());

        whenDriverProducesValue("foo", 1.0d);

        thenAssetOutputContains(0, "foo", 1.0d);
    }

    @ParameterizedTest
    @EnumSource(TriggerMode.class)
    public void shouldApplyScaleToDouble(TriggerMode triggerMode) {
        this.triggerMode = triggerMode;
        givenAssetChannel("foo", DataType.DOUBLE, ScaleOffsetType.DOUBLE, Optional.of(3.0d), Optional.empty());

        whenDriverProducesValue("foo", 1.0d);

        thenAssetOutputContains(0, "foo", 3.0d);
    }

    @ParameterizedTest
    @EnumSource(TriggerMode.class)
    public void shouldApplyScaleToFloat(TriggerMode triggerMode) {
        this.triggerMode = triggerMode;
        givenAssetChannel("foo", DataType.FLOAT, ScaleOffsetType.DOUBLE, Optional.of(3.0d), Optional.empty());

        whenDriverProducesValue("foo", 1.0f);

        thenAssetOutputContains(0, "foo", 3.0f);
    }

    @ParameterizedTest
    @EnumSource(TriggerMode.class)
    public void shouldApplyScaleToInteger(TriggerMode triggerMode) {
        this.triggerMode = triggerMode;
        givenAssetChannel("foo", DataType.INTEGER, ScaleOffsetType.DOUBLE, Optional.of(3.0d), Optional.empty());

        whenDriverProducesValue("foo", 1);

        thenAssetOutputContains(0, "foo", 3);
    }

    @ParameterizedTest
    @EnumSource(TriggerMode.class)
    public void shouldApplyScaleToIntegerWithDoubleScale(TriggerMode triggerMode) {
        this.triggerMode = triggerMode;
        givenAssetChannel("foo", DataType.INTEGER, ScaleOffsetType.DOUBLE, Optional.of(3.0f), Optional.empty());

        whenDriverProducesValue("foo", 1);

        thenAssetOutputContains(0, "foo", 3);
    }

    @ParameterizedTest
    @EnumSource(TriggerMode.class)
    public void shouldApplyScaleToIntegerWithIntegerScale(TriggerMode triggerMode) {
        this.triggerMode = triggerMode;
        givenAssetChannel("foo", DataType.INTEGER, ScaleOffsetType.DEFINED_BY_VALUE_TYPE, Optional.of(3),
                Optional.empty());

        whenDriverProducesValue("foo", 1);

        thenAssetOutputContains(0, "foo", 3);
    }

    @ParameterizedTest
    @EnumSource(TriggerMode.class)
    public void shouldApplyScaleToLongWithDoubleScale(TriggerMode triggerMode) {
        this.triggerMode = triggerMode;
        givenAssetChannel("foo", DataType.LONG, ScaleOffsetType.DOUBLE, Optional.of(3.0d), Optional.empty());

        whenDriverProducesValue("foo", 1l);

        thenAssetOutputContains(0, "foo", 3l);
    }

    @ParameterizedTest
    @EnumSource(TriggerMode.class)
    public void shouldApplyScaleToLongWithLongScale(TriggerMode triggerMode) {
        this.triggerMode = triggerMode;
        givenAssetChannel("foo", DataType.LONG, ScaleOffsetType.LONG, Optional.of(3), Optional.empty());

        whenDriverProducesValue("foo", 40l);

        thenAssetOutputContains(0, "foo", 120l);
    }

    @ParameterizedTest
    @EnumSource(TriggerMode.class)
    public void shouldApplyOffsetToLongWithLongScale(TriggerMode triggerMode) {
        this.triggerMode = triggerMode;
        givenAssetChannel("foo", DataType.LONG, ScaleOffsetType.LONG, Optional.empty(), Optional.of(55));

        whenDriverProducesValue("foo", 40l);

        thenAssetOutputContains(0, "foo", 95l);
    }

    @ParameterizedTest
    @EnumSource(TriggerMode.class)
    public void shouldApplyBothScaleAndOffsetToLongWithLongScale(TriggerMode triggerMode) {
        this.triggerMode = triggerMode;
        givenAssetChannel("foo", DataType.LONG, ScaleOffsetType.LONG, Optional.of(2), Optional.of(55));

        whenDriverProducesValue("foo", 40l);

        thenAssetOutputContains(0, "foo", 135l);
    }

    @ParameterizedTest
    @EnumSource(TriggerMode.class)
    public void shouldApplyOffsetToDouble(TriggerMode triggerMode) {
        this.triggerMode = triggerMode;
        givenAssetChannel("foo", DataType.DOUBLE, ScaleOffsetType.DOUBLE, Optional.empty(), Optional.of(10.0d));

        whenDriverProducesValue("foo", 1.0d);

        thenAssetOutputContains(0, "foo", 11.0d);
    }

    @ParameterizedTest
    @EnumSource(TriggerMode.class)
    public void shouldApplyOffsetToFloat(TriggerMode triggerMode) {
        this.triggerMode = triggerMode;
        givenAssetChannel("foo", DataType.FLOAT, ScaleOffsetType.DOUBLE, Optional.empty(), Optional.of(-2.0d));

        whenDriverProducesValue("foo", 1.0f);

        thenAssetOutputContains(0, "foo", -1.0f);
    }

    @ParameterizedTest
    @EnumSource(TriggerMode.class)
    public void shouldApplyOffsetToIngeger(TriggerMode triggerMode) {
        this.triggerMode = triggerMode;
        givenAssetChannel("foo", DataType.INTEGER, ScaleOffsetType.DOUBLE, Optional.empty(), Optional.of(10.0d));

        whenDriverProducesValue("foo", 1);

        thenAssetOutputContains(0, "foo", 11);
    }

    @ParameterizedTest
    @EnumSource(TriggerMode.class)
    public void shouldApplyOffsetToLong(TriggerMode triggerMode) {
        this.triggerMode = triggerMode;
        givenAssetChannel("foo", DataType.LONG, ScaleOffsetType.DOUBLE, Optional.empty(), Optional.of(-2.0d));

        whenDriverProducesValue("foo", 1l);

        thenAssetOutputContains(0, "foo", -1l);
    }

    @ParameterizedTest
    @EnumSource(TriggerMode.class)
    public void shouldApplyBothScaleAndOffset(TriggerMode triggerMode) {
        this.triggerMode = triggerMode;
        givenAssetChannel("foo", DataType.LONG, ScaleOffsetType.DOUBLE, Optional.of(6.0f), Optional.of(-2.0d));

        whenDriverProducesValue("foo", 2l);

        thenAssetOutputContains(0, "foo", 10l);
    }

    @ParameterizedTest
    @EnumSource(TriggerMode.class)
    public void shouldTolerateScaleAndOffsetOnBoolean(TriggerMode triggerMode) {
        this.triggerMode = triggerMode;
        givenAssetChannel("foo", DataType.BOOLEAN, ScaleOffsetType.DOUBLE, Optional.of(6.0f), Optional.of(-2.0d));

        whenDriverProducesValue("foo", true);

        thenAssetOutputContains(0, "foo", true);
    }

    @ParameterizedTest
    @EnumSource(TriggerMode.class)
    public void shouldTolerateScaleAndOffsetOnString(TriggerMode triggerMode) {
        this.triggerMode = triggerMode;
        givenAssetChannel("foo", DataType.STRING, ScaleOffsetType.DOUBLE, Optional.of(6.0f), Optional.of(-2.0d));

        whenDriverProducesValue("foo", "bar");

        thenAssetOutputContains(0, "foo", "bar");
    }

    @ParameterizedTest
    @EnumSource(TriggerMode.class)
    public void shouldTolerateScaleAndOffsetOnByteArray(TriggerMode triggerMode) {
        this.triggerMode = triggerMode;
        givenAssetChannel("foo", DataType.BYTE_ARRAY, ScaleOffsetType.DOUBLE, Optional.of(6.0f), Optional.of(-2.0d));

        whenDriverProducesValue("foo", new byte[] { 1, 2, 3, 4 });

        thenAssetOutputContains(0, "foo", new byte[] { 1, 2, 3, 4 });
    }


    private enum TriggerMode {
        READ,
        LISTEN
    }

    private TriggerMode triggerMode;


    private void givenAssetChannel(String name, DataType dataType, ScaleOffsetType scaleOffsetType,
            Optional<? extends Number> scale, Optional<? extends Number> offset) {
        super.givenAssetChannel(name, this.triggerMode == TriggerMode.LISTEN, dataType, scaleOffsetType, scale, offset);
    }

    private void whenDriverProducesValue(final String channelName, final Object value) {
        if (this.triggerMode == TriggerMode.READ) {
            givenChannelValues(channelName, value);
            whenAssetReceivesEnvelopes(1);
        } else {
            whenDriverEmitsEvents(channelName, value);
        }
    }
}
