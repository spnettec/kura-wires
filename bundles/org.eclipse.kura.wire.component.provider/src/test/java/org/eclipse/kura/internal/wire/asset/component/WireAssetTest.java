/*******************************************************************************
 * Copyright (c) 2017, 2026 Eurotech and/or its affiliates and others
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 ******************************************************************************/
package org.eclipse.kura.internal.wire.asset.component;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.eclipse.kura.asset.provider.AssetConstants;
import org.eclipse.kura.channel.ChannelFlag;
import org.eclipse.kura.channel.ChannelRecord;
import org.eclipse.kura.channel.ChannelStatus;
import org.eclipse.kura.channel.ChannelType;
import org.eclipse.kura.configuration.ConfigurationService;
import org.eclipse.kura.core.testutil.TestUtil;
import org.eclipse.kura.driver.Driver;
import org.eclipse.kura.internal.wire.asset.TimestampMode;
import org.eclipse.kura.internal.wire.asset.WireAssetOptions;
import org.eclipse.kura.type.BooleanValue;
import org.eclipse.kura.type.DataType;
import org.eclipse.kura.type.IntegerValue;
import org.eclipse.kura.type.LongValue;
import org.eclipse.kura.type.StringValue;
import org.eclipse.kura.type.TypedValue;
import org.eclipse.kura.wire.WireEnvelope;
import org.eclipse.kura.wire.WireHelperService;
import org.eclipse.kura.wire.WireRecord;
import org.eclipse.kura.wire.WireSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.ComponentContext;

class WireAssetTest {

    private final WireAsset asset = new WireAsset();
    private final Driver driver = mock(Driver.class);
    private final WireSupport support = mock(WireSupport.class);
    private final ComponentContext context = mock(ComponentContext.class);
    private final Map<String, Object> properties = new HashMap<>();
    private boolean activated;

    @BeforeEach
    void configure() {
        this.properties.put(AssetConstants.ASSET_DESC_PROP.value(), "description");
        this.properties.put(AssetConstants.ASSET_DRIVER_PROP.value(), "driverPid");
        this.properties.put(ConfigurationService.KURA_SERVICE_PID, "componentName");
        this.properties.put("request.timeout", 5000);
        when(this.context.getBundleContext()).thenReturn(mock(BundleContext.class));
        when(this.driver.getChannelDescriptor()).thenReturn(Collections::emptyList);
        WireHelperService helper = mock(WireHelperService.class);
        when(helper.newWireSupport(any(), any())).thenReturn(this.support);
        this.asset.bindWireHelperService(helper);
    }

    @AfterEach
    void deactivate() throws Exception {
        if (!this.activated) {
            return;
        }
        var executor = this.asset.getBaseAssetExecutor();
        ExecutorService io = (ExecutorService) TestUtil.getFieldValue(executor, "ioExecutor");
        ExecutorService config = (ExecutorService) TestUtil.getFieldValue(executor, "configExecutor");
        try {
            this.asset.unsetDriver();
            executor.runConfig(() -> { }).get(5, TimeUnit.SECONDS);
        } finally {
            this.asset.deactivate(this.context);
            assertTrue(io.awaitTermination(5, TimeUnit.SECONDS), "Asset IO executor must terminate");
            assertTrue(config.awaitTermination(5, TimeUnit.SECONDS), "Asset config executor must terminate");
        }
    }

    @Test
    void testOnWireReceive() throws Exception {
        channel("readChannel1", ChannelType.READ, DataType.BOOLEAN);
        channel("writeChannel2", ChannelType.WRITE, DataType.BOOLEAN);
        this.properties.put(WireAssetOptions.TIMESTAMP_MODE_PROP_NAME, TimestampMode.PER_CHANNEL.name());
        doAnswer(invocation -> {
            List<ChannelRecord> records = invocation.getArgument(0);
            records.forEach(record -> success(record, new BooleanValue(true), 42));
            return null;
        }).when(this.driver).read(any());
        doAnswer(invocation -> {
            List<ChannelRecord> records = invocation.getArgument(0);
            records.forEach(record -> success(record, record.getValue(), 111));
            return null;
        }).when(this.driver).write(any());
        activate();

        this.asset.onWireReceive(new WireEnvelope("pid", List.of(new WireRecord(Map.of(
                "writeChannel2_assetName", new StringValue("componentName"), "writeChannel2", new BooleanValue(true))))));

        assertEquals(Map.of("assetName", new StringValue("componentName"), "readChannel1", new BooleanValue(true),
                "readChannel1_timestamp", new LongValue(42)), emitted());
        ArgumentCaptor<List<ChannelRecord>> records = ArgumentCaptor.forClass(List.class);
        verify(this.driver).read(records.capture());
        assertEquals(1, records.getValue().size());
        assertEquals("readChannel1", records.getValue().get(0).getChannelName());
        assertEquals(5000, records.getValue().get(0).getChannelConfig().get("request.timeout"));
        verify(this.driver).write(records.capture());
        assertEquals(1, records.getValue().size());
        ChannelRecord written = records.getValue().get(0);
        assertEquals("writeChannel2", written.getChannelName());
        assertEquals(DataType.BOOLEAN, written.getValueType());
        assertEquals(new BooleanValue(true), written.getValue());
        assertEquals(5000, written.getChannelConfig().get("request.timeout"));
    }

    @ParameterizedTest
    @EnumSource(TimestampMode.class)
    void testTimestampModes(TimestampMode mode) throws Exception {
        for (int i = 0; i < 3; i++) {
            channel(Integer.toString(i), ChannelType.READ, DataType.INTEGER);
        }
        long[] timestamps = {84, 22, 150};
        doAnswer(invocation -> {
            List<ChannelRecord> records = invocation.getArgument(0);
            for (ChannelRecord record : records) {
                int index = Integer.parseInt(record.getChannelName());
                success(record, new IntegerValue(index), timestamps[index]);
            }
            return null;
        }).when(this.driver).read(any());
        activate();
        this.properties.put(WireAssetOptions.TIMESTAMP_MODE_PROP_NAME, mode.name());
        this.asset.updated(this.properties);
        // DS rebinds the tracked driver after configuration updates.
        this.asset.setDriver(this.driver);
        this.asset.getBaseAssetExecutor().runConfig(() -> { }).get(5, TimeUnit.SECONDS);
        long before = System.currentTimeMillis();
        this.asset.onWireReceive(new WireEnvelope("pid", List.of()));
        long after = System.currentTimeMillis();

        Map<String, TypedValue<?>> expected = new HashMap<>();
        expected.put("assetName", new StringValue("componentName"));
        for (int i = 0; i < 3; i++) {
            expected.put(Integer.toString(i), new IntegerValue(i));
        }
        Map<String, TypedValue<?>> actual = emitted();
        switch (mode) {
        case PER_CHANNEL:
            for (int i = 0; i < 3; i++) {
                expected.put(i + "_timestamp", new LongValue(timestamps[i]));
            }
            break;
        case SINGLE_DRIVER_GENERATED_MAX:
            expected.put("assetTimestamp", new LongValue(150));
            break;
        case SINGLE_DRIVER_GENERATED_MIN:
            expected.put("assetTimestamp", new LongValue(22));
            break;
        case SINGLE_ASSET_GENERATED:
            LongValue generated = assertInstanceOf(LongValue.class, actual.get("assetTimestamp"));
            assertTrue(generated.getValue() >= before && generated.getValue() <= after);
            expected.put("assetTimestamp", generated);
            break;
        case NO_TIMESTAMPS:
            break;
        }
        assertEquals(expected, actual);
        verify(this.driver).read(any());
    }

    private void activate() throws Exception {
        this.activated = true;
        this.asset.activate(this.context, this.properties);
        this.asset.setDriver(this.driver);
        this.asset.getBaseAssetExecutor().runConfig(() -> { }).get(5, TimeUnit.SECONDS);
    }

    private Map<String, TypedValue<?>> emitted() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(this.support).emit(captor.capture());
        return assertInstanceOf(WireRecord.class, captor.getValue()).getProperties();
    }

    private void channel(String name, ChannelType type, DataType valueType) {
        this.properties.put(name + "#+name", name);
        this.properties.put(name + "#+type", type.name());
        this.properties.put(name + "#+value.type", valueType.name());
        this.properties.put(name + "#+enabled", "true");
        this.properties.put(name + "#+listen", "false");
    }

    private static void success(ChannelRecord record, TypedValue<?> value, long timestamp) {
        record.setValue(value);
        record.setTimestamp(timestamp);
        record.setChannelStatus(new ChannelStatus(ChannelFlag.SUCCESS));
    }
}
