# Upstream test restoration

Upstream baseline: `ac6d6159` (the full revision is recorded in the central Kura source inventory).

The first ordinary Maven batch passes 129 JUnit 5 cases with Maven 3.10 and JDK 21:

| Module | Cases |
| --- | ---: |
| Asset helper | 8 |
| Asset cloudlet | 13 |
| Asset REST | 9 |
| AI component/options/tensor adapter | 26 |
| Cloud publisher/subscriber and regex filter | 15 |
| Database store/filter | 18 |
| Script engine/options/bindings/context reset | 40 |

The same upstream batch contributes three channel-record conversion cases to the retained core `org.eclipse.kura.asset.provider` module. They also verify that the fork's request timeout is preserved.

Fixtures follow the current contracts: asset discovery uses service references and `kura.service.pid`, database tests use isolated in-memory H2 instances, and trackers, component lifecycles and Graal contexts are closed after tests. The Graal JavaScript implementation and H2 driver are test-scope dependencies at the existing production versions. No remote cloud, inference server or hardware is contacted.

Asset REST retains the upstream JSON assertions. Its Surefire JVM opens `java.lang` and `java.util`, matching the upstream test parent and the existing development runtime. Direct IDEA JUnit execution of this suite needs the same VM options:

```text
--add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED
```

Three upstream CloudPublisher cases are excluded explicitly: absent basic/full position services and dynamic position unbinding. The fork's hand-written DS descriptor requires a static `PositionService` with cardinality `1..1`; upstream uses an optional dynamic reference. Test restoration does not change that production contract. The exact methods are recorded in the central inventory.

```sh
mvn -pl :org.eclipse.kura.asset.helper.provider,:org.eclipse.kura.asset.cloudlet.provider,:org.eclipse.kura.rest.asset.provider,:org.eclipse.kura.wire.ai.component.provider,:org.eclipse.kura.wire.component.provider,:org.eclipse.kura.wire.db.component.provider,:org.eclipse.kura.wire.script.tools -am test
```

The remaining timer, FIFO, wire-asset, database-container and REST transport scenarios still need fixture/harness review. This report does not claim full Wires or real OSGi acceptance.
