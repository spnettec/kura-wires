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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.eclipse.kura.channel.ChannelType;
import org.eclipse.kura.channel.ScaleOffsetType;
import org.eclipse.kura.core.testutil.TestUtil;
import org.eclipse.kura.driver.Driver.ConnectionException;
import org.eclipse.kura.type.DataType;
import org.eclipse.kura.type.TypedValue;
import org.eclipse.kura.type.TypedValues;
import org.eclipse.kura.wire.WireEnvelope;
import org.eclipse.kura.wire.WireHelperService;
import org.eclipse.kura.wire.WireRecord;
import org.eclipse.kura.wire.WireSupport;
import org.junit.jupiter.api.AfterEach;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.ComponentContext;

/** Component fixture; deliberately does not emulate OSGi graph/service registration. */
public class WireAssetTestBase {

    private final WireAsset asset = new WireAsset();
    private final ComponentContext context = mock(ComponentContext.class);
    private final MockDriver driver = new MockDriver();
    private final List<WireEnvelope> envelopes = new ArrayList<>();
    private boolean activated;

    @AfterEach
    void deactivateAsset() throws Exception {
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
        assertTrue(this.driver.listeners.isEmpty(), "Driver listeners must be released");
    }

    protected void givenAssetChannel(final String name, final boolean listen, final DataType dataType,
            final ScaleOffsetType scaleOffsetType, final Optional<? extends Number> scale,
            final Optional<? extends Number> offset) {
        final Map<String, Object> config = new HashMap<>();

        config.put("driver.pid", "testDriver");
        config.put(name + "#+name", name);
        config.put(name + "#+type", ChannelType.READ.name());
        config.put(name + "#+value.type", dataType.name());
        config.put(name + "#+scaleoffset.type", scaleOffsetType.name());
        config.put(name + "#+enabled", true);
        config.put(name + "#+listen", listen);

        if (scale.isPresent()) {
            config.put(name + "#+scale", scale.get().toString());
        }

        if (offset.isPresent()) {
            config.put(name + "#+offset", offset.get().toString());
        }

        givenAssetConfig(config);
    }

    protected void givenAssetConfig(final Map<String, Object> assetConfig) {
        Map<String, Object> config = new HashMap<>(assetConfig);
        config.put("driver.pid", "testDriver");
        config.put("kura.service.pid", "testAsset");
        config.put("request.timeout", 5000);
        // ConfigurationAdmin supplied these OCD defaults in the upstream harness.
        config.putIfAbsent("timestamp.mode", "PER_CHANNEL");
        for (String key : assetConfig.keySet()) {
            if (key.endsWith("#+name")) {
                String name = key.substring(0, key.length() - "#+name".length());
                config.putIfAbsent(name + "#+enabled", true);
                config.putIfAbsent(name + "#+listen", false);
            }
        }
        when(this.context.getBundleContext()).thenReturn(mock(BundleContext.class));
        WireHelperService helper = mock(WireHelperService.class);
        WireSupport support = mock(WireSupport.class);
        when(helper.newWireSupport(any(), any())).thenReturn(support);
        doAnswer(invocation -> {
            Object output = invocation.getArgument(0);
            List<WireRecord> records;
            if (output instanceof WireRecord record) {
                records = List.of(record);
            } else {
                records = (List<WireRecord>) output;
            }
            this.envelopes.add(new WireEnvelope("testAsset", records));
            return null;
        }).when(support).emit(any());
        this.asset.bindWireHelperService(helper);
        this.activated = true;
        this.asset.activate(this.context, config);
        this.asset.setDriver(this.driver);
        try {
            this.asset.getBaseAssetExecutor().runConfig(() -> { }).get(5, TimeUnit.SECONDS);
            this.driver.preparedReadCalled.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("Asset driver did not become ready", e);
        }
        long expectedListeners = config.entrySet().stream()
                .filter(e -> e.getKey().endsWith("#+listen") && Boolean.TRUE.equals(e.getValue())).count();
        assertEquals(expectedListeners, this.driver.listeners.size(), "Every listen channel must be registered");
    }

    protected void givenChannelValue(final String key, final Object value) {
        this.driver.addReadResult(key, TypedValues.newTypedValue(value));
    }

    protected void givenChannelValues(final String key, final Object... values) {
        for (final Object value : values) {
            givenChannelValue(key, value);
        }
    }

    protected void givenConnectionException(final ConnectionException e) {
        this.driver.throwConnectionException(Optional.of(e));
    }

    protected void givenNoConnectionException() {
        this.driver.throwConnectionException(Optional.empty());
    }

    protected void whenAssetReceivesEnvelope() {

        this.asset.onWireReceive(new WireEnvelope("emitter", List.of()));
    }

    protected void whenDriverEmitsEvents(final Object... values) {
        final Iterator<Object> iter = Arrays.asList(values).iterator();

        while (iter.hasNext()) {
            final String channelName = (String) iter.next();
            final TypedValue<?> value = TypedValues.newTypedValue(iter.next());

            this.driver.emitChannelEvent(channelName, value);
        }
    }

    protected void whenAssetReceivesEnvelopes(final int count) {
        for (int i = 0; i < count; i++) {
            whenAssetReceivesEnvelope();
        }
    }

    protected WireEnvelope awaitEnvelope(final int index) {
        // onWireReceive waits for IO and listener callbacks are synchronous in this fixture.
        assertTrue(index < this.envelopes.size(), "Expected envelope " + index);
        return this.envelopes.get(index);
    }

    protected void thenAssetOutputContains(final int index, final Object... properties) {
        awaitEnvelope(index);

        final WireEnvelope envelope = this.envelopes.get(index);

        final Iterator<Object> iter = Arrays.asList(properties).iterator();

        while (iter.hasNext()) {
            final String key = (String) iter.next();
            final TypedValue<?> value = TypedValues.newTypedValue(iter.next());

            assertEquals(value, envelope.getRecords().get(0).getProperties().get(key));
        }
    }

    protected void thenAssetOutputContainsKey(final int index, final String key) {
        awaitEnvelope(index);

        final WireEnvelope envelope = this.envelopes.get(index);

        assertTrue(envelope.getRecords().get(0).getProperties().containsKey(key));
    }

    protected void thenAssetOutputPropertyCountIs(final int index, final int expectedCount) {
        awaitEnvelope(index);

        final WireEnvelope envelope = this.envelopes.get(index);

        assertEquals(expectedCount, envelope.getRecords().get(0).getProperties().size());
    }

    protected void thenAssetOutputDoesNotContain(final int index, final String... properties) {
        awaitEnvelope(index);

        final WireEnvelope envelope = this.envelopes.get(index);

        for (String key : Arrays.asList(properties)) {
            assertFalse(envelope.getRecords().get(0).getProperties().containsKey(key));
        }
    }

    protected void thenTotalEmittedEnvelopeCountIs(final int expectedCount) {
        assertEquals(expectedCount, this.envelopes.size());
    }

    protected Map<String, Object> map(final Object... values) {
        final Map<String, Object> result = new HashMap<>();

        for (int i = 0; i < values.length; i += 2) {
            result.put((String) values[i], values[i + 1]);
        }

        return result;
    }
}
