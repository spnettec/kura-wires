/*******************************************************************************
 * Copyright (c) 2026 Contributors to the Eclipse Foundation
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0, https://www.eclipse.org/legal/epl-2.0/
 * SPDX-License-Identifier: EPL-2.0
 ******************************************************************************/
package org.eclipse.kura.rest.wire.provider.test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.eclipse.kura.cloudconnection.request.RequestHandlerMessageConstants.ARGS_KEY;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

import org.eclipse.kura.KuraException;
import org.eclipse.kura.asset.Asset;
import org.eclipse.kura.cloudconnection.message.KuraMessage;
import org.eclipse.kura.configuration.*;
import org.eclipse.kura.configuration.metatype.OCDService;
import org.eclipse.kura.core.configuration.ComponentConfigurationImpl;
import org.eclipse.kura.core.configuration.metatype.*;
import org.eclipse.kura.crypto.CryptoService;
import org.eclipse.kura.driver.Driver;
import org.eclipse.kura.driver.descriptor.*;
import org.eclipse.kura.internal.rest.wire.WireRestService;
import org.eclipse.kura.internal.wire.WireGraphServiceImpl;
import org.eclipse.kura.internal.json.marshaller.unmarshaller.JsonMarshallUnmarshallImpl;
import org.eclipse.kura.message.*;
import org.eclipse.kura.request.handler.jaxrs.JaxRsRequestHandlerProxy;
import org.eclipse.kura.wire.WireComponent;
import org.eclipse.kura.wire.graph.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.osgi.framework.*;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.runtime.ServiceComponentRuntime;
import org.osgi.service.component.runtime.dto.ComponentDescriptionDTO;
import org.osgi.service.wireadmin.*;

import com.eclipsesource.json.*;

/** Configuration and registry boundaries are test doubles; endpoint, DTO and graph logic are real. */
public abstract class RestGraphFixture {
    private static final String GRAPH = "org.eclipse.kura.wire.graph.WireGraphService";
    private static final String EMITTER = "org.eclipse.kura.util.wire.test.TestEmitterReceiver";
    private static final String DRIVER = "org.eclipse.kura.util.test.driver.ChannelDescriptorTestDriver";
    private static final String ASSET = "org.eclipse.kura.wire.WireAsset";
    private final Map<String, ComponentConfiguration> configs = new LinkedHashMap<>();
    private final Map<String, Object> instances = new HashMap<>();
    private final Map<String, ServiceReference<?>> references = new LinkedHashMap<>();
    private final Map<ServiceReference<?>, Object> serviceInstances = new IdentityHashMap<>();
    private final Set<String> trackedDeletes = new HashSet<>();
    private final Map<String, Class<?>> factories = Map.of(EMITTER, WireComponent.class,
            "org.eclipse.kura.wire.Timer", WireComponent.class, "org.eclipse.kura.wire.Logger", WireComponent.class,
            DRIVER, Driver.class, ASSET, Asset.class);
    private final BundleContext context = mock(BundleContext.class);
    private final ConfigurationService configuration = mock(ConfigurationService.class);
    private final JsonMarshallUnmarshallImpl json = new JsonMarshallUnmarshallImpl();
    private final Graph graph = new Graph();
    private JaxRsRequestHandlerProxy proxy;
    protected WireRestService endpoint;
    private KuraMessage response;
    private ServiceReference<?> scrReference;

    @BeforeEach
    protected void startFixture() throws Exception {
        when(this.context.createFilter(anyString())).thenAnswer(i -> FrameworkUtil.createFilter(i.getArgument(0)));
        when(this.context.getServiceReferences(nullable(String.class), nullable(String.class)))
                .thenAnswer(i -> findReferences(i.getArgument(0), i.getArgument(1)));
        when(this.context.getAllServiceReferences(nullable(String.class), nullable(String.class)))
                .thenAnswer(i -> findReferences(i.getArgument(0), i.getArgument(1)));
        when(this.context.getServiceReferences(any(Class.class), nullable(String.class)))
                .thenAnswer(i -> Arrays.asList(findReferences(((Class<?>) i.getArgument(0)).getName(), i.getArgument(1))));
        // The graph's WireAdmin boundary is not part of these endpoint tests.
        when(this.context.getServiceReferences(eq(WireComponent.class), nullable(String.class))).thenReturn(List.of());
        when(this.context.getService(any())).thenAnswer(i -> this.serviceInstances.get(i.getArgument(0)));
        ServiceComponentRuntime scr = mock(ServiceComponentRuntime.class);
        this.scrReference = mock(ServiceReference.class);
        this.serviceInstances.put(this.scrReference, scr);
        when(scr.getComponentDescriptionDTOs()).thenAnswer(i -> this.factories.entrySet().stream().map(e -> {
            ComponentDescriptionDTO dto = new ComponentDescriptionDTO();
            dto.name = e.getKey();
            dto.serviceInterfaces = new String[] {e.getValue().getName()};
            return dto;
        }).toList());

        when(this.configuration.getComponentConfiguration(anyString()))
                .thenAnswer(i -> this.configs.get(i.getArgument(0)));
        when(this.configuration.getComponentConfigurations(any(Filter.class))).thenAnswer(i -> {
            Filter filter = i.getArgument(0);
            return this.configs.values().stream().filter(c -> filter.matches(registryProperties(c))).toList();
        });
        doAnswer(i -> { save(i.getArgument(1), i.getArgument(0), i.getArgument(2)); return null; })
                .when(this.configuration).createFactoryConfiguration(anyString(), anyString(), anyMap(), anyBoolean());
        doAnswer(i -> { update(i.getArgument(0), i.getArgument(1)); return null; })
                .when(this.configuration).updateConfiguration(anyString(), anyMap(), anyBoolean());
        doAnswer(i -> {
            for (ComponentConfiguration c : (List<ComponentConfiguration>) i.getArgument(0)) {
                update(c.getPid(), c.getConfigurationProperties());
            }
            return null;
        }).when(this.configuration).updateConfigurations(anyList(), anyBoolean());
        doAnswer(i -> {
            String pid = i.getArgument(0);
            this.configs.remove(pid);
            this.instances.remove(pid);
            this.serviceInstances.remove(this.references.remove(pid));
            return null;
        }).when(this.configuration).deleteFactoryConfiguration(anyString(), anyBoolean());

        WireAdmin admin = mock(WireAdmin.class);
        when(admin.getWires(null)).thenReturn(new Wire[0]);
        this.graph.bindWireAdmin(admin);
        this.graph.setConfigurationService(this.configuration);
        this.configs.put(GRAPH, new ComponentConfigurationImpl(GRAPH, null,
                new HashMap<>(Map.of("WireGraph", "{\"components\":[],\"wires\":[]}"))));
        this.graph.start();

        WireRestService endpoint = new WireRestService();
        this.endpoint = endpoint;
        ComponentContext componentContext = mock(ComponentContext.class);
        when(componentContext.getBundleContext()).thenReturn(this.context);
        endpoint.activate(componentContext);
        endpoint.setWireGraphService(this.graph);
        endpoint.setConfigurationService(this.configuration);
        endpoint.setJsonMarshaller(this.json);
        endpoint.setJsonUnmarshaller(this.json);
        endpoint.setCryptoService(mock(CryptoService.class));
        WireComponentDefinitionService definitions = mock(WireComponentDefinitionService.class);
        when(definitions.getComponentDefinitions()).thenReturn(List.of(emitterDefinition()));
        endpoint.setWireComponentDefinifitionService(definitions);
        OCDService ocds = mock(OCDService.class);
        when(ocds.getServiceProviderOCDs(Driver.class)).thenReturn(List.of(driverDefinition()));
        endpoint.setOCDService(ocds);
        DriverDescriptorService descriptors = mock(DriverDescriptorService.class);
        when(descriptors.listDriverDescriptors()).thenAnswer(i -> this.configs.values().stream()
                .filter(c -> DRIVER.equals(c.getConfigurationProperties().get("service.factoryPid")))
                .map(c -> new DriverDescriptor(c.getPid(), DRIVER,
                        new ChannelDescriptorTestDriver().getChannelDescriptor().getDescriptor())).toList());
        endpoint.setDriverDescriptorService(descriptors);
        this.proxy = new JaxRsRequestHandlerProxy(endpoint);
    }

    @AfterEach
    protected void closeFixture() { this.graph.stop(); }

    private ServiceReference<?>[] findReferences(String type, String expression) throws Exception {
        if (ServiceComponentRuntime.class.getName().equals(type)) {
            return new ServiceReference<?>[] {this.scrReference};
        }
        Filter filter = expression == null ? null : FrameworkUtil.createFilter(expression);
        return this.configs.values().stream().filter(c -> this.references.containsKey(c.getPid()))
                .filter(c -> type == null || Arrays.asList((String[]) registryProperties(c).get("objectClass")).contains(type))
                .filter(c -> filter == null || filter.matches(registryProperties(c)))
                .map(c -> this.references.get(c.getPid())).toArray(ServiceReference<?>[]::new);
    }

    private Map<String, Object> registryProperties(ComponentConfiguration c) {
        Map<String, Object> result = new HashMap<>(c.getConfigurationProperties());
        String factory = (String) result.get("service.factoryPid");
        Class<?> type = factory == null ? null : this.factories.get(factory);
        result.put("objectClass", type == null ? new String[0] : new String[] {type.getName()});
        return result;
    }

    private void save(String pid, String factory, Map<String, Object> properties) {
        Map<String, Object> merged = new HashMap<>();
        if ("org.eclipse.kura.wire.Timer".equals(factory)) { merged.put("simple.interval", 10); }
        merged.putAll(properties);
        merged.put("service.factoryPid", factory);
        merged.put("kura.service.pid", pid);
        this.configs.put(pid, new ComponentConfigurationImpl(pid, null, merged));
        Object instance = mock(this.factories.getOrDefault(factory, Object.class));
        this.instances.put(pid, instance);
        ServiceReference<?> ref = mock(ServiceReference.class);
        when(ref.getProperty(anyString())).thenAnswer(i -> registryProperties(this.configs.get(pid)).get(i.getArgument(0)));
        this.serviceInstances.remove(this.references.put(pid, ref));
        this.serviceInstances.put(ref, instance);
    }

    private void update(String pid, Map<String, Object> properties) {
        Map<String, Object> merged = new HashMap<>();
        if (this.configs.containsKey(pid)) { merged.putAll(this.configs.get(pid).getConfigurationProperties()); }
        merged.putAll(properties);
        if (GRAPH.equals(pid)) {
            this.configs.put(pid, new ComponentConfigurationImpl(pid, null, merged));
            this.graph.apply(merged);
        } else {
            save(pid, (String) merged.get("service.factoryPid"), merged);
        }
    }

    protected void givenEmptyWireGraph() {
        try { this.graph.delete(); } catch (Exception e) { throw new AssertionError(e); }
    }
    protected void givenNoFactoryComponentsWithPid(String pid) {
        try { this.configuration.deleteFactoryConfiguration(pid, true); } catch (Exception e) { throw new AssertionError(e); }
    }
    protected void givenFactoryComponent(String pid, String factory, Map<String, Object> properties, Class<?>... ignored) {
        save(pid, factory, properties);
    }
    protected void givenDeleteTrackerForPid(String pid) {
        assertTrue(this.configs.containsKey(pid));
        this.trackedDeletes.add(pid);
    }
    protected void givenWireGraphWith(Consumer<GraphBuilder>... customizers) {
        GraphBuilder builder = new GraphBuilder();
        for (var customizer : customizers) { customizer.accept(builder); }
        try { this.graph.update(new WireGraphConfiguration(builder.components, builder.wires)); }
        catch (Exception e) { throw new AssertionError("Graph fixture could not start", e); }
    }
    protected static Consumer<GraphBuilder> wireComponent(String pid, String factory) {
        return b -> b.components.add(new WireComponentConfiguration(
                new ComponentConfigurationImpl(pid, null, new HashMap<>(Map.of("service.factoryPid", factory))),
                new HashMap<>(Map.of("inputPortCount", 1, "outputPortCount", 1))));
    }
    protected static Consumer<GraphBuilder> testEmitterReceiver(String pid) { return wireComponent(pid, EMITTER); }
    protected static Consumer<GraphBuilder> wire(String emitter, String receiver) {
        return b -> b.wires.add(new MultiportWireConfiguration(emitter, receiver, 0, 0));
    }
    protected static class GraphBuilder {
        final List<WireComponentConfiguration> components = new ArrayList<>();
        final List<MultiportWireConfiguration> wires = new ArrayList<>();
    }

    protected record MethodSpec(String method, String... alternative) { }
    protected void whenRequestIsPerformed(MethodSpec method, String path) { whenRequestIsPerformed(method, path, null); }
    protected void whenRequestIsPerformed(MethodSpec method, String path, String body) {
        this.response = performRequest(method, path, body);
    }

    /** Override only the transport; retain the same graph setup and semantic assertions. */
    protected KuraMessage performRequest(MethodSpec method, String path, String body) {
        KuraPayload payload = new KuraPayload();
        if (body != null) { payload.setBody(body.getBytes(StandardCharsets.UTF_8)); }
        KuraMessage request = new KuraMessage(payload);
        request.getProperties().put(ARGS_KEY.value(), Arrays.asList(path.substring(1).split("/")));
        try {
            return switch (method.method()) {
            case "GET" -> this.proxy.doGet(null, request);
            case "PUT" -> this.proxy.doPut(null, request);
            case "POST" -> this.proxy.doPost(null, request);
            case "DELETE" -> this.proxy.doDel(null, request);
            default -> throw new AssertionError("Unsupported test method " + method);
            };
        } catch (Exception e) { throw new AssertionError("Request failed", e); }
    }
    protected void thenRequestSucceeds() { thenResponseCodeIs(200); }
    protected void thenResponseCodeIs(int expected) {
        assertNotNull(this.response);
        assertEquals(expected, new KuraResponsePayload(this.response.getPayload()).getResponseCode(),
                () -> new KuraResponsePayload(this.response.getPayload()).getExceptionStack());
    }
    private JsonValue responseJson() {
        return Json.parse(new String(this.response.getPayload().getBody(), StandardCharsets.UTF_8));
    }
    protected void thenResponseBodyEqualsJson(String expected) {
        // Gson canonicalizes JSON objects independently of map iteration order.
        assertEquals(com.google.gson.JsonParser.parseString(expected),
                com.google.gson.JsonParser.parseString(responseJson().toString()));
    }
    protected void thenResponseElementIs(JsonValue expected, JsonProjection projection) {
        assertEquals(expected, projection.apply(responseJson()), () -> responseJson().toString());
    }
    protected void thenResponseElementExists(JsonProjection projection) {
        assertNotNull(projection.apply(responseJson()), () -> responseJson().toString());
    }
    protected void thenResponseElementDoesNotExists(JsonProjection projection) {
        assertNull(projection.apply(responseJson()), () -> responseJson().toString());
    }
    private WireGraphConfiguration currentGraph() {
        try { return this.graph.get(); } catch (Exception e) { throw new AssertionError(e); }
    }
    private WireComponentConfiguration component(String pid) {
        return currentGraph().getWireComponentConfigurations().stream()
                .filter(c -> pid.equals(c.getConfiguration().getPid())).findFirst().orElseThrow();
    }
    protected void thenWireGraphIsEmpty() {
        assertEquals(0, currentGraph().getWireComponentConfigurations().size());
        assertEquals(0, currentGraph().getWireConfigurations().size());
    }
    protected void thenCurrentGraphContainsComponent(String pid) { assertNotNull(component(pid)); }
    protected void thenCurrentGraphDoesNotContainsComponent(String pid) {
        assertTrue(currentGraph().getWireComponentConfigurations().stream().noneMatch(c -> pid.equals(c.getConfiguration().getPid())));
    }
    protected void thenCurrentGraphContainsWire(String emitter, String receiver) {
        assertTrue(currentGraph().getWireConfigurations().stream()
                .anyMatch(c -> emitter.equals(c.getEmitterPid()) && receiver.equals(c.getReceiverPid())));
    }
    protected void thenWireComponentPropertyEquals(String pid, String key, Object expected) {
        assertEquals(expected, component(pid).getProperties().get(key));
    }
    protected void thenWireComponentConfigurationPropertyEquals(String pid, String key, Object expected) {
        assertEquals(expected, component(pid).getConfiguration().getConfigurationProperties().get(key));
    }
    protected void thenComponentConfigurationEquals(String pid, String key, Object expected) {
        assertNotNull(this.configs.get(pid));
        assertEquals(expected, this.configs.get(pid).getConfigurationProperties().get(key));
    }
    protected void thenComponentConfigurationWasRequested(String pid, Class<?> type) {
        assertTrue(type.isInstance(this.instances.get(pid)), "Expected configuration request for " + pid);
        assertNotNull(this.configs.get(pid));
        try {
            verify(this.configuration).createFactoryConfiguration(anyString(), eq(pid), anyMap(), eq(false));
        } catch (KuraException e) {
            throw new AssertionError(e);
        }
    }
    protected void thenTrackedComponentIsDeleted(String pid) {
        assertTrue(this.trackedDeletes.remove(pid));
        assertFalse(this.configs.containsKey(pid));
        assertFalse(this.references.containsKey(pid));
    }

    private static WireComponentDefinition emitterDefinition() {
        // Values from the upstream TestEmitterReceiver handwritten DS/metatype fixtures.
        WireComponentDefinition d = new WireComponentDefinition();
        d.setFactoryPid(EMITTER);
        d.setMinInputPorts(1); d.setMaxInputPorts(1); d.setDefaultInputPorts(1);
        d.setMinOutputPorts(1); d.setMaxOutputPorts(1); d.setDefaultOutputPorts(1);
        Tocd ocd = new Tocd(); ocd.setId(EMITTER); ocd.setName("Test Emitter Receiver");
        d.setComponentOCD(new ComponentConfigurationImpl(EMITTER, ocd, Map.of()));
        return d;
    }
    private static ComponentConfiguration driverDefinition() {
        // Values from upstream ChannelDescriptorTestDriverOptions; independent of the expected REST JSON.
        Tocd ocd = new Tocd(); ocd.setId(DRIVER); ocd.setName("ChannelDescriptorTestDriver");
        ocd.setDescription("A driver for testing channel descriptor properties");
        Tad ad = new Tad(); ad.setId("test.property"); ad.setName("Test Property");
        ad.setDescription("A test property"); ad.setType(Tscalar.STRING); ad.setDefault("test value");
        ocd.addAD(ad);
        return new ComponentConfigurationImpl(DRIVER, ocd, Map.of("test.property", "test value"));
    }
    private final class Graph extends WireGraphServiceImpl {
        void start() {
            ComponentContext c = mock(ComponentContext.class); when(c.getBundleContext()).thenReturn(context);
            activate(c, configs.get(GRAPH).getConfigurationProperties());
        }
        void stop() { deactivate(null); }
        void apply(Map<String, Object> properties) { updated(properties); }
        @Override protected <T> T unmarshal(String value, Class<T> type) {
            try { return json.unmarshal(value, type); } catch (Exception e) { throw new AssertionError(e); }
        }
        @Override protected String marshal(Object value) {
            try { return json.marshal(value); } catch (Exception e) { throw new AssertionError(e); }
        }
    }
}
