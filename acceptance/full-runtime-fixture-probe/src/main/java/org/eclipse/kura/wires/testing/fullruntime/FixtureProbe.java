/* SPDX-License-Identifier: EPL-2.0 */
package org.eclipse.kura.wires.testing.fullruntime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import org.eclipse.kura.configuration.*;
import org.eclipse.kura.configuration.metatype.AD;
import org.eclipse.kura.configuration.metatype.OCD;
import org.eclipse.kura.driver.Driver;
import org.eclipse.kura.driver.descriptor.DriverDescriptorService;
import org.eclipse.kura.system.SystemService;
import org.eclipse.kura.type.TypedValues;
import org.eclipse.kura.wire.*;
import org.eclipse.kura.wire.graph.*;
import org.osgi.framework.*;
import org.osgi.service.component.runtime.ServiceComponentRuntime;
import org.osgi.service.component.runtime.dto.ComponentConfigurationDTO;
import org.osgi.service.wireadmin.WireAdmin;

/** Complete Mac application: real SCR/OCD and production descriptor/graph services. */
public final class FixtureProbe implements BundleActivator {
    private static final String DRIVER = "org.eclipse.kura.util.test.driver.ChannelDescriptorTestDriver";
    private static final String EMITTER = "org.eclipse.kura.util.wire.test.TestEmitterReceiver";
    private final List<ServiceReference<?>> references = new ArrayList<>();
    private Thread worker;

    public void start(BundleContext context) {
        worker = new Thread(() -> run(context), "CompleteMacWireFixtureAcceptance");
        worker.start();
    }
    public void stop(BundleContext context) throws InterruptedException {
        if (worker != null && worker.isAlive()) { worker.interrupt(); worker.join(5000); }
    }

    private void run(BundleContext context) {
        Path home = Path.of(System.getProperty("kura.home")).toAbsolutePath().normalize();
        Path archive = Path.of(System.getProperty("kura.acceptance.root")).toAbsolutePath().normalize();
        Map<String, Object> evidence = new LinkedHashMap<>();
        String prefix = "acceptance.mac.wirefixtures." + UUID.randomUUID();
        String driver = prefix + ".driver", emitter = prefix + ".emitter", receiver = prefix + ".receiver";
        ConfigurationService configuration = null;
        WireGraphService graph = null;
        WireGraphConfiguration initial = null;
        boolean driverCreated = false, graphChanged = false;
        Throwable failure = null;
        try {
            require(home.startsWith(archive) && !home.equals(archive)
                    && Files.isRegularFile(home.resolve(".wire-fixture-acceptance-owned")), "Owned isolated profile");
            require(context.getBundles().length >= 278, "Complete production application plus acceptance helper");
            SystemService system = service(context, SystemService.class, null);
            require(system.getClass().getName().equals("org.eclipse.kura.core.system.SystemServiceImpl"), "Actual host SystemService");
            require(Path.of(system.getKuraHome()).toAbsolutePath().normalize().equals(home), "Actual owned home");
            configuration = service(context, ConfigurationService.class, null);
            ServiceComponentRuntime scr = service(context, ServiceComponentRuntime.class, null);
            await(() -> scr.getComponentDescriptionDTO(context.getBundle(), DRIVER) != null
                    && scr.getComponentDescriptionDTO(context.getBundle(), EMITTER) != null, "Fixture DS descriptions");
            final ConfigurationService cfg = configuration;
            await(() -> cfg.getFactoryComponentPids().containsAll(List.of(DRIVER, EMITTER)), "OCD factory discovery");
            ComponentConfiguration defaults = configuration.getDefaultComponentConfiguration(DRIVER);
            require("test value".equals(defaults.getConfigurationProperties().get("test.property")), "Historical driver OCD default");
            require(defaults.getDefinition().getAD().size() == 1, "Historical driver OCD attribute");
            configuration.createFactoryConfiguration(DRIVER, driver, new HashMap<>(), true);
            driverCreated = true;
            service(context, Driver.class, "(kura.service.pid=" + driver + ")");
            assertActive(scr, context.getBundle(), DRIVER, driver);
            configuration.updateConfiguration(driver, new HashMap<>(Map.of("test.property", "Mac SCR 更新")), false);
            await(() -> {
                try {
                    var refs = context.getServiceReferences(Driver.class, "(kura.service.pid=" + driver + ")");
                    return refs.stream().anyMatch(r -> "Mac SCR 更新".equals(r.getProperty("test.property")));
                } catch (Exception e) { throw new IllegalStateException(e); }
            }, "Real driver SCR modification");
            DriverDescriptorService descriptors = service(context, DriverDescriptorService.class, null);
            var descriptor = descriptors.getDriverDescriptor(driver).orElseThrow();
            require(DRIVER.equals(descriptor.getFactoryPid()), "Driver factory identity");
            require("Mac SCR 更新".equals(descriptor.getComConfig().getConfigurationProperties().get("test.property")), "Descriptor actual configuration");
            @SuppressWarnings("unchecked") List<AD> ads = (List<AD>) descriptor.getChannelDescriptor();
            Map<String, String> minima = Map.of("STRING", "foo", "BYTE", "10", "CHAR", "b", "DOUBLE", "13.5",
                    "FLOAT", "13.5", "INTEGER", "-200000", "LONG", "-2147493648", "SHORT", "-20000");
            for (var min : minima.entrySet()) {
                AD ad = ads.stream().filter(a -> (min.getKey() + ".prop.min.max").equals(a.getId())).findFirst().orElseThrow();
                require(min.getValue().equals(ad.getMin()), "Descriptor minimum " + min.getKey());
            }
            WireComponentDefinitionService definitions = service(context, WireComponentDefinitionService.class, null);
            var definition = definitions.getComponentDefinitions().stream().filter(d -> EMITTER.equals(d.getFactoryPid())).findFirst().orElseThrow();
            require(definition.getMinInputPorts() == 1 && definition.getMaxInputPorts() == 1 && definition.getDefaultInputPorts() == 1
                    && definition.getMinOutputPorts() == 1 && definition.getMaxOutputPorts() == 1 && definition.getDefaultOutputPorts() == 1,
                    "Historical one-input/one-output SCR metadata");
            require(definition.getComponentOCD() != null && definition.getComponentOCD().getDefinition().getAD().isEmpty(), "Historical empty emitter OCD");
            graph = service(context, WireGraphService.class, null);
            initial = graph.get();
            require(initial.getWireComponentConfigurations().isEmpty() && initial.getWireConfigurations().isEmpty(), "Owned empty initial graph");
            graphChanged = true;
            graph.update(new WireGraphConfiguration(List.of(component(configuration, emitter), component(configuration, receiver)),
                    List.of(new MultiportWireConfiguration(emitter, receiver, 0, 0))));
            TestEmitterReceiver source = service(context, TestEmitterReceiver.class, "(kura.service.pid=" + emitter + ")");
            TestEmitterReceiver target = service(context, TestEmitterReceiver.class, "(kura.service.pid=" + receiver + ")");
            assertActive(scr, context.getBundle(), EMITTER, emitter);
            assertActive(scr, context.getBundle(), EMITTER, receiver);
            WireAdmin admin = service(context, WireAdmin.class, null);
            await(() -> wireCount(admin) == 1, "Actual WireAdmin route");
            for (int n = 0; n < 3; n++) {
                source.emit(List.of(new WireRecord(Map.of("sequence", TypedValues.newIntegerValue(n), "nonce", TypedValues.newStringValue(prefix)))));
                WireEnvelope received = target.await();
                require(received != null && received.getRecords().size() == 1, "WireEnvelope arrival " + n);
                var record = received.getRecords().get(0).getProperties();
                require(TypedValues.newIntegerValue(n).equals(record.get("sequence"))
                        && TypedValues.newStringValue(prefix).equals(record.get("nonce")), "Exact real WireAdmin payload " + n);
            }
            require(target.updateCount() >= 3 && source.consumerConnectionCount() > 0 && target.producerConnectionCount() > 0,
                    "Actual Producer/Consumer callbacks");
            // Production defaults allow 443/4443. This owned acceptance profile uses 18443.
            configuration.updateConfiguration("org.eclipse.kura.internal.rest.provider.RestService",
                    new HashMap<>(Map.of("allowed.ports", new Integer[] {18443})), false);
            evidence.put("restAllowedPorts", List.of(18443));
            evidence.put("driverPid", driver); evidence.put("emitterPid", emitter); evidence.put("receiverPid", receiver);
            evidence.put("bundleCount", context.getBundles().length); evidence.put("driverMinimumBounds", minima);
            evidence.put("driverAttributeCount", ads.size()); evidence.put("driverScrActiveAndUpdated", true);
            evidence.put("fixtureScrActive", true); evidence.put("actualOcdAndPortMetadata", true);
            evidence.put("wireEnvelopeDeliveries", 3); evidence.put("consumerUpdatedCallbacks", target.updateCount());
            Files.writeString(archive.resolve("wire-probe-ready.json"), new Gson().toJson(evidence) + "\n");
            await(() -> Files.isRegularFile(archive.resolve("https-result.json")), "Independent authenticated HTTPS assertions");
            var https = JsonParser.parseString(Files.readString(archive.resolve("https-result.json"))).getAsJsonObject();
            require(https.get("passed").getAsBoolean(), "Independent HTTPS metadata/descriptor/graph validation");
            evidence.put("https", https);
        } catch (Throwable error) {
            failure = error;
        } finally {
            try {
                if (graphChanged) {
                    graph.update(initial);
                    WireAdmin admin = service(context, WireAdmin.class, null);
                    require(graph.get().getWireComponentConfigurations().isEmpty() && graph.get().getWireConfigurations().isEmpty(), "Graph cleanup");
                    require(admin.getWires(null) == null || admin.getWires(null).length == 0, "WireAdmin cleanup");
                    require(context.getServiceReferences(TestEmitterReceiver.class, "(kura.service.pid=" + emitter + ")").isEmpty()
                            && context.getServiceReferences(TestEmitterReceiver.class, "(kura.service.pid=" + receiver + ")").isEmpty(), "Emitter/receiver service cleanup");
                }
                if (driverCreated) {
                    configuration.deleteFactoryConfiguration(driver, true);
                    require(context.getServiceReferences(Driver.class, "(kura.service.pid=" + driver + ")").isEmpty(), "Driver service cleanup");
                }
                if (configuration != null) {
                    require(Collections.disjoint(configuration.getConfigurableComponentPids(), List.of(driver, emitter, receiver)), "Owned configuration cleanup");
                }
                evidence.put("cleanupPassed", true);
            } catch (Throwable cleanup) {
                if (failure == null) { failure = cleanup; } else { failure.addSuppressed(cleanup); }
            }
            for (var reference : references) { context.ungetService(reference); }
        }
        evidence.put("passed", failure == null);
        if (failure != null) { evidence.put("error", failure.toString()); failure.printStackTrace(); }
        try { Files.writeString(archive.resolve("wire-probe-result.json"), new Gson().toJson(evidence) + "\n"); }
        catch (Exception error) { error.printStackTrace(); }
    }

    private static void assertActive(ServiceComponentRuntime scr, Bundle bundle, String name, String pid) throws Exception {
        await(() -> scr.getComponentConfigurationDTOs(scr.getComponentDescriptionDTO(bundle, name)).stream()
                .anyMatch(c -> c.state == ComponentConfigurationDTO.ACTIVE && pid.equals(c.properties.get("kura.service.pid"))), "ACTIVE SCR instance " + pid);
    }
    private static WireComponentConfiguration component(ConfigurationService configuration, String pid) throws Exception {
        ComponentConfiguration defaults = configuration.getDefaultComponentConfiguration(EMITTER);
        Map<String, Object> properties = new HashMap<>(defaults.getConfigurationProperties());
        properties.put("service.factoryPid", EMITTER); properties.put("kura.service.pid", pid);
        ComponentConfiguration config = new ComponentConfiguration() {
            public String getPid() { return pid; }
            public OCD getDefinition() { return defaults.getDefinition(); }
            public OCD getLocalizedDefinition(String locale) { return defaults.getLocalizedDefinition(locale); }
            public Map<String, Object> getConfigurationProperties() { return properties; }
        };
        return new WireComponentConfiguration(config, Map.of("inputPortCount", 1, "outputPortCount", 1));
    }
    private <T> T service(BundleContext context, Class<T> type, String filter) throws Exception {
        await(() -> { try { return !context.getServiceReferences(type, filter).isEmpty(); } catch (Exception e) { throw new IllegalStateException(e); } }, "Service " + type.getName());
        var reference = context.getServiceReferences(type, filter).iterator().next();
        references.add(reference); T value = context.getService(reference); require(value != null, "Available service " + type.getName()); return value;
    }
    private static void await(BooleanSupplier condition, String label) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            require(System.nanoTime() < deadline, "Timed out: " + label); Thread.sleep(25);
        }
    }
    private static void require(boolean condition, String label) { if (!condition) { throw new IllegalStateException(label); } }
    private static int wireCount(WireAdmin admin) {
        try { var wires = admin.getWires(null); return wires == null ? 0 : wires.length; }
        catch (InvalidSyntaxException error) { throw new IllegalStateException(error); }
    }
}
