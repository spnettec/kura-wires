/*******************************************************************************
 * Copyright (c) 2022, 2026 Eurotech and/or its affiliates and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 at https://www.eclipse.org/legal/epl-2.0/
 * SPDX-License-Identifier: EPL-2.0
 ******************************************************************************/
package org.eclipse.kura.internal.wire.timer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.eclipse.kura.core.testutil.TestUtil;
import org.eclipse.kura.internal.wire.helper.WireHelperServiceImpl;
import org.eclipse.kura.type.LongValue;
import org.eclipse.kura.wire.WireComponent;
import org.eclipse.kura.wire.WireHelperService;
import org.eclipse.kura.wire.WireRecord;
import org.eclipse.kura.wire.WireSupport;
import org.eclipse.kura.wire.graph.Constants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.event.EventHandler;
import org.osgi.service.wireadmin.Wire;
import org.quartz.Scheduler;

class TimerTest {

    private final Timer timer = new Timer();
    private final ComponentContext context = mock(ComponentContext.class);
    private final WireSupport support = mock(WireSupport.class);
    private final List<ScheduledExecutorService> realExecutors = new ArrayList<>();

    @BeforeEach
    void bindSupport() {
        WireHelperService helper = mock(WireHelperService.class);
        when(helper.newWireSupport(any(), any())).thenReturn(this.support);
        this.timer.bindWireHelperService(helper);
    }

    @AfterEach
    void stopTimer() throws Exception {
        this.timer.deactivate();
        for (ScheduledExecutorService executor : this.realExecutors) {
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Timer worker must terminate");
        }
    }

    @Test
    void shouldSupportDefaultConfig() {
        try (var scheduling = new Scheduling()) {
            this.timer.activate(this.context, defaults());
            Runnable tick = scheduling.tick(10_000, 10_000);
            verifyNoInteractions(this.support);
            long before = System.currentTimeMillis();
            tick.run();
            assertTimerRecord(before);
        }
    }

    @Test
    void shouldTickEvery100Millis() throws Exception {
        assertRealTicks(100);
    }

    @Test
    void shouldTickEvery10Millis() throws Exception {
        assertRealTicks(10);
    }

    @Test
    void shouldSupportFixedRateScheduling() {
        try (var scheduling = new Scheduling()) {
            this.timer.activate(this.context, simple(100));
            Runnable tick = scheduling.tick(100, 100);
            for (int i = 0; i < 10; i++) {
                tick.run();
            }
            verify(this.support, times(10)).emit(any());
            verify(scheduling.executor, never()).scheduleWithFixedDelay(any(), anyLong(), anyLong(), any());
        }
    }

    @Test
    void shouldSupportReconfiguration() {
        try (var scheduling = new Scheduling()) {
            this.timer.activate(this.context, simple(100));
            scheduling.tick(100, 100).run();
            this.timer.updated(simple(50));
            var order = inOrder(scheduling.executor);
            order.verify(scheduling.executor).scheduleAtFixedRate(any(), eq(100L), eq(100L), eq(TimeUnit.MILLISECONDS));
            order.verify(scheduling.executor).shutdownNow();
            order.verify(scheduling.executor).scheduleAtFixedRate(any(), eq(50L), eq(50L), eq(TimeUnit.MILLISECONDS));
            scheduling.tick(50, 50).run();
            verify(this.support, times(2)).emit(any());
        }
    }

    @Test
    void shouldNotTickOnUpdateWithDefaultConfig() {
        try (var scheduling = new Scheduling()) {
            this.timer.activate(this.context, defaults());
            for (int i = 0; i < 10; i++) {
                this.timer.updated(simple(10_000));
            }
            verify(scheduling.executor, times(11)).scheduleAtFixedRate(any(), eq(10_000L), eq(10_000L),
                    eq(TimeUnit.MILLISECONDS));
            verify(this.support, never()).emit(any());
        }
    }

    @Test
    void shouldSupportCustomFirstTickInterval() {
        Map<String, Object> config = defaults();
        config.put("simple.interval", 60);
        config.put("simple.first.tick.policy", "CUSTOM");
        config.put("simple.custom.first.tick.interval", 2);
        try (var scheduling = new Scheduling()) {
            this.timer.activate(this.context, config);
            scheduling.tick(2_000, 60_000).run();
            verify(this.support).emit(any());
        }
    }

    @Test
    void shouldContinueToTickIfReceiverThrows() throws Exception {
        ServiceReference<WireComponent> reference = mock(ServiceReference.class);
        when(reference.getProperty("service.pid")).thenReturn("testTimer");
        when(reference.getProperty("kura.service.pid")).thenReturn("testTimer");
        when(this.context.getServiceReference()).thenReturn((ServiceReference) reference);
        this.timer.bindWireHelperService(new WireHelperServiceImpl());
        Wire wire = mock(Wire.class);
        Dictionary<String, Object> properties = new Hashtable<>();
        properties.put(Constants.WIRE_EMITTER_PORT_PROP_NAME.value(), 0);
        when(wire.getProperties()).thenReturn(properties);
        CountDownLatch calls = new CountDownLatch(3);
        doAnswer(invocation -> {
            calls.countDown();
            throw new IllegalStateException("upstream receiver failure fixture");
        }).when(wire).update(any());
        activateReal(simple(10));
        this.timer.consumersConnected(new Wire[] {wire});
        assertTrue(calls.await(5, TimeUnit.SECONDS), "Receiver exceptions must not cancel future timer ticks");
    }

    @Test
    void shouldSupportCronExpression() throws Exception {
        try (var cron = new CronRegistry()) {
            BlockingQueue<Object> ticks = captureTicks();
            this.timer.activate(this.context, cronConfig("0/1 * * * * ?"));
            cron.rememberScheduler();
            assertNotNull(ticks.poll(5, TimeUnit.SECONDS));
            assertNotNull(ticks.poll(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void shouldTolerateInvalidCronExpression() throws Exception {
        try (var cron = new CronRegistry()) {
            BlockingQueue<Object> ticks = captureTicks();
            assertDoesNotThrow(() -> this.timer.activate(this.context, cronConfig("invalid")));
            assertTrue(((Optional<?>) TestUtil.getFieldValue(this.timer, "timerExecutor")).isEmpty());
            assertTrue(ticks.isEmpty());
            this.timer.updated(cronConfig("0/1 * * * * ?"));
            cron.rememberScheduler();
            assertNotNull(ticks.poll(5, TimeUnit.SECONDS), "A valid update must restart cron emission");
        }
    }

    private void assertRealTicks(int interval) throws Exception {
        try (var scheduling = new Scheduling()) {
            this.timer.activate(this.context, simple(interval));
            scheduling.tick(interval, interval);
        }
        BlockingQueue<Object> ticks = captureTicks();
        long before = System.currentTimeMillis();
        activateReal(simple(interval));
        for (int i = 0; i < 3; i++) {
            Object output = ticks.poll(5, TimeUnit.SECONDS);
            assertNotNull(output, "Expected a scheduled tick");
            List<?> records = assertInstanceOf(List.class, output);
            assertEquals(1, records.size());
            var record = assertInstanceOf(WireRecord.class, records.get(0));
            long timestamp = assertInstanceOf(LongValue.class, record.getProperties().get("TIMER")).getValue();
            assertTrue(timestamp >= before);
        }
    }

    private void activateReal(Map<String, Object> config) throws Exception {
        this.timer.activate(this.context, config);
        Optional<?> timerExecutor = (Optional<?>) TestUtil.getFieldValue(this.timer, "timerExecutor");
        assertTrue(timerExecutor.isPresent());
        this.realExecutors.add((ScheduledExecutorService) TestUtil.getFieldValue(timerExecutor.get(), "executor"));
    }

    private BlockingQueue<Object> captureTicks() {
        BlockingQueue<Object> ticks = new LinkedBlockingQueue<>();
        doAnswer(invocation -> {
            ticks.add(invocation.getArgument(0));
            return null;
        }).when(this.support).emit(any());
        return ticks;
    }

    private void assertTimerRecord(long before) {
        ArgumentCaptor<Object> output = ArgumentCaptor.forClass(Object.class);
        verify(this.support).emit(output.capture());
        List<?> records = assertInstanceOf(List.class, output.getValue());
        assertEquals(1, records.size());
        WireRecord record = assertInstanceOf(WireRecord.class, records.get(0));
        assertEquals(1, record.getProperties().size());
        long timestamp = assertInstanceOf(LongValue.class, record.getProperties().get("TIMER")).getValue();
        assertTrue(timestamp >= before && timestamp <= System.currentTimeMillis());
    }

    private static Map<String, Object> defaults() {
        return new HashMap<>(Map.of("kura.service.pid", "testTimer", "type", "SIMPLE", "simple.interval", 10,
                "simple.time.unit", "SECONDS", "simple.first.tick.policy", "DEFAULT"));
    }

    private static Map<String, Object> simple(int millis) {
        Map<String, Object> config = defaults();
        config.put("simple.interval", millis);
        config.put("simple.time.unit", "MILLISECONDS");
        return config;
    }

    private static Map<String, Object> cronConfig(String expression) {
        Map<String, Object> config = defaults();
        config.put("type", "CRON");
        config.put("cron.interval", expression);
        return config;
    }

    private final class Scheduling implements AutoCloseable {
        private final ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
        private final MockedStatic<Executors> factory = mockStatic(Executors.class);

        Scheduling() {
            this.factory.when(() -> Executors.newSingleThreadScheduledExecutor(any(ThreadFactory.class)))
                    .thenReturn(this.executor);
        }

        Runnable tick(long firstDelay, long period) {
            ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
            verify(this.executor).scheduleAtFixedRate(task.capture(), eq(firstDelay), eq(period), eq(TimeUnit.MILLISECONDS));
            return task.getValue();
        }

        @Override
        public void close() {
            try {
                TimerTest.this.timer.deactivate();
                verify(this.executor, atLeastOnce()).shutdownNow();
            } finally {
                this.factory.close();
            }
        }
    }

    /** Only the registry boundary is mocked; Quartz uses its real in-memory scheduler. */
    private final class CronRegistry implements AutoCloseable {
        private final MockedStatic<FrameworkUtil> framework = mockStatic(FrameworkUtil.class);
        private final ServiceRegistration<EventHandler> registration = mock(ServiceRegistration.class);
        private final Object manager;
        private Scheduler scheduler;
        private List<Thread> schedulerThreads = List.of();

        CronRegistry() throws Exception {
            Bundle bundle = mock(Bundle.class);
            BundleContext bundleContext = mock(BundleContext.class);
            when(bundle.getBundleContext()).thenReturn(bundleContext);
            when(bundleContext.registerService(eq(EventHandler.class), any(EventHandler.class), any(Dictionary.class)))
                    .thenReturn(this.registration);
            this.framework.when(() -> FrameworkUtil.getBundle(CronTimerExecutor.class)).thenReturn(bundle);
            var field = CronTimerExecutor.class.getDeclaredField("schedulerManager");
            field.setAccessible(true);
            this.manager = field.get(null);
        }

        void rememberScheduler() throws Exception {
            Optional<?> schedulerRef = (Optional<?>) TestUtil.getFieldValue(this.manager, "scheduler");
            assertTrue(schedulerRef.isPresent(), "Cron must create a scheduler");
            this.scheduler = (Scheduler) schedulerRef.get();
            String prefix = this.scheduler.getSchedulerName() + "_";
            this.schedulerThreads = Thread.getAllStackTraces().keySet().stream()
                    .filter(thread -> thread.getName().startsWith(prefix)).toList();
            assertFalse(this.schedulerThreads.isEmpty(), "Quartz must have live scheduler workers");
        }

        @Override
        public void close() throws Exception {
            try {
                TimerTest.this.timer.deactivate();
                if (this.scheduler != null) {
                    this.scheduler.shutdown(true);
                    assertTrue(this.scheduler.isShutdown());
                    for (Thread thread : this.schedulerThreads) {
                        thread.join(5000);
                        assertFalse(thread.isAlive(), "Quartz worker must stop: " + thread.getName());
                    }
                    verify(this.registration).unregister();
                }
                assertEquals(0, TestUtil.getFieldValue(this.manager, "instanceCount"));
                assertTrue(((Optional<?>) TestUtil.getFieldValue(this.manager, "scheduler")).isEmpty());
                assertTrue(((Optional<?>) TestUtil.getFieldValue(this.manager, "clockChangeEventHandler")).isEmpty());
            } finally {
                this.framework.close();
            }
        }
    }
}
