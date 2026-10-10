/* SPDX-License-Identifier: EPL-2.0 */
package org.eclipse.kura.wires.testing.fullruntime;

import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.kura.configuration.ConfigurableComponent;
import org.eclipse.kura.wire.*;
import org.eclipse.kura.wire.graph.MultiportWireSupport;
import org.osgi.framework.ServiceReference;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.wireadmin.Wire;

/** Acceptance-only equivalent of the upstream helper's real WireSupport callbacks. */
public final class TestEmitterReceiver implements WireEmitter, WireReceiver, ConfigurableComponent {
    private WireHelperService helper;
    private MultiportWireSupport support;
    private final LinkedBlockingQueue<WireEnvelope> received = new LinkedBlockingQueue<>();
    private final AtomicInteger updates = new AtomicInteger();
    private final AtomicInteger producerConnections = new AtomicInteger();
    private final AtomicInteger consumerConnections = new AtomicInteger();

    public void bindWireHelperService(WireHelperService value) { helper = value; }
    @SuppressWarnings("unchecked")
    public void activate(ComponentContext context) {
        support = (MultiportWireSupport) helper.newWireSupport(this,
                (ServiceReference<WireComponent>) context.getServiceReference());
    }
    public void consumersConnected(Wire[] wires) {
        consumerConnections.incrementAndGet(); support.consumersConnected(wires);
    }
    public Object polled(Wire wire) { return support.polled(wire); }
    public void producersConnected(Wire[] wires) {
        producerConnections.incrementAndGet(); support.producersConnected(wires);
    }
    public void updated(Wire wire, Object value) { updates.incrementAndGet(); support.updated(wire, value); }
    public void onWireReceive(Object envelope) { received.add((WireEnvelope) envelope); }
    public void emit(List<WireRecord> records) { support.emit(records); }
    public WireEnvelope await() throws InterruptedException { return received.poll(5, TimeUnit.SECONDS); }
    public int updateCount() { return updates.get(); }
    public int producerConnectionCount() { return producerConnections.get(); }
    public int consumerConnectionCount() { return consumerConnections.get(); }
}
