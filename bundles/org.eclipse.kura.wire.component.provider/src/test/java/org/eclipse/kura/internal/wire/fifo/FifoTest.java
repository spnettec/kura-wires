/*******************************************************************************
 * Copyright (c) 2017, 2026 Eurotech and/or its affiliates and others
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 ******************************************************************************/
package org.eclipse.kura.internal.wire.fifo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.kura.core.testutil.TestUtil;
import org.eclipse.kura.type.LongValue;
import org.eclipse.kura.wire.WireEnvelope;
import org.eclipse.kura.wire.WireHelperService;
import org.eclipse.kura.wire.WireRecord;
import org.eclipse.kura.wire.WireSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.osgi.service.component.ComponentContext;

class FifoTest {

    private final Fifo fifo = new Fifo();
    private final BlockingQueue<Object> emitted = new LinkedBlockingQueue<>();
    private final CountDownLatch firstEmission = new CountDownLatch(1);
    private final CountDownLatch releaseEmission = new CountDownLatch(1);
    private Thread emitterThread;

    @BeforeEach
    void bindSupport() {
        WireSupport support = mock(WireSupport.class);
        WireHelperService helper = mock(WireHelperService.class);
        when(helper.newWireSupport(any(), any())).thenReturn(support);
        this.fifo.bindWireHelperService(helper);
        doAnswer(invocation -> {
            this.firstEmission.countDown();
            if (!this.releaseEmission.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Test did not release FIFO emission");
            }
            this.emitted.add(invocation.getArgument(0));
            return null;
        }).when(support).emit(any());
    }

    @AfterEach
    void stopEmitter() throws Exception {
        this.releaseEmission.countDown();
        this.fifo.deactivate();
        if (this.emitterThread != null) {
            this.emitterThread.join(5000);
            assertFalse(this.emitterThread.isAlive(), "FIFO emitter must stop after deactivation");
        }
    }

    @Test
    void testMulti() throws Exception {
        start(false);
        List<WireEnvelope> envelopes = envelopes();
        this.fifo.onWireReceive(envelopes.get(0));
        assertTrue(this.firstEmission.await(5, TimeUnit.SECONDS));
        for (int i = 1; i <= 5; i++) {
            this.fifo.onWireReceive(envelopes.get(i));
        }
        var producer = Executors.newSingleThreadExecutor();
        try {
            var completion = producer.submit(() -> {
                for (int i = 6; i < envelopes.size(); i++) {
                    this.fifo.onWireReceive(envelopes.get(i));
                }
            });
            this.releaseEmission.countDown();
            completion.get(5, TimeUnit.SECONDS);
            for (WireEnvelope envelope : envelopes) {
                assertSame(envelope, this.emitted.poll(5, TimeUnit.SECONDS), "FIFO must preserve order and payload");
            }
            assertTrue(this.emitted.isEmpty());
        } finally {
            producer.shutdownNow();
            assertTrue(producer.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void testMultiDiscard() throws Exception {
        start(true);
        List<WireEnvelope> envelopes = envelopes();
        this.fifo.onWireReceive(envelopes.get(0));
        assertTrue(this.firstEmission.await(5, TimeUnit.SECONDS));
        // The emitter is held outside the queue: exactly five further envelopes fit.
        for (int i = 1; i < envelopes.size(); i++) {
            this.fifo.onWireReceive(envelopes.get(i));
        }
        this.releaseEmission.countDown();
        for (int i = 0; i <= 5; i++) {
            assertSame(envelopes.get(i), this.emitted.poll(5, TimeUnit.SECONDS));
        }
        WireEnvelope sentinel = envelope(10);
        this.fifo.onWireReceive(sentinel);
        assertSame(sentinel, this.emitted.poll(5, TimeUnit.SECONDS), "Overflow must be discarded before the sentinel");
        assertEquals(0, this.emitted.size());
    }

    private void start(boolean discard) throws Exception {
        this.fifo.activate(Map.of("discard.envelopes", discard, "queue.capacity", 5), mock(ComponentContext.class));
        this.emitterThread = (Thread) TestUtil.getFieldValue(this.fifo, "emitterThread");
    }

    private static List<WireEnvelope> envelopes() {
        List<WireEnvelope> result = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            result.add(envelope(i));
        }
        return result;
    }

    private static WireEnvelope envelope(long sequence) {
        return new WireEnvelope("emitter", List.of(new WireRecord(Map.of("sequence", new LongValue(sequence)))));
    }
}
