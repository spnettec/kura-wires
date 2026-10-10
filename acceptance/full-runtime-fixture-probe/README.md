# Complete Mac historical Wires fixture acceptance

Acceptance-only bundle outside the default sibling reactor and Debian packages.
It compiles the existing migrated `ChannelDescriptorTestDriver` source directly,
without a second source copy, and provides handwritten fixture DS/OCD resources.
Driver `test.property` and the one-input/one-output, empty emitter OCD reproduce
the metadata represented by the existing Jupiter `RestGraphFixture`.

The emitter/receiver retains upstream Producer/Consumer delegation to production
WireHelperService. Its acceptance queue replaces the upstream global callback;
`onWireReceive(Object)` and `WireSupport.emit(records)` use the current YOFC API.
It does not change production WireSupport, PID handling or event semantics.

The complete Maven Mac application supplies real SystemServiceImpl,
ConfigurationService, SCR/OCD, DriverDescriptorService, WireGraphService and
WireAdmin. The probe checks ACTIVE fixture instances, driver defaults and actual
configuration update, all eight descriptor minimum bounds, real port metadata,
three ordered envelopes with unique payloads and actual WireAdmin callbacks.
An independent authenticated HTTPS client reads the same live driver/OCD/graph.
It then asserts deletion of all owned configurations, services and wires.

## Run

Use Maven 3.10, JDK 21 and the same absolute Maven cache as the stopped template:

```sh
mvn -f acceptance/full-runtime-fixture-probe/pom.xml package \
  -DskipTests -DskipITs -Dmaven.repo.local=/absolute/cache

python3 acceptance/full-runtime-fixture-probe/run.py \
  --runtime /absolute/stopped-complete-runtime \
  --template-profile /absolute/isolated-template-profile \
  --archive /absolute/new-acceptance-archive \
  --java /absolute/jdk21/bin/java
```

The template uses the isolated acceptance ports 18480/18443/18444 and public
`admin:admin` test credentials. The runner creates a new owned home and Equinox
configuration area, relocates bootstrap keystore paths, provisions a localhost
certificate only in the owned HTTPS keystore copy, retains failed evidence,
awaits the owned JVM and checks ports are released. It does not clean or rebuild
the template runtime or use the personal `~/.kura-dev` profile.
The probe explicitly configures the owned REST `allowed.ports` to 18443 through
the production ConfigurationService; production defaults remain 443/4443.

These executable acceptance assertions are separate from JUnit 5 engine counts.
Do not add them to historical workspace invocation totals. This stage verifies
loopback HTTPS authentication, certificate/hostname validation and REST integration.
Installed Debian, Linux, hardware and production
driver IO are outside this fixture check. No deleted driver or global legacy
harness is restored to production.
