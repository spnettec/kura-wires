#!/usr/bin/env python3
"""Run historical fixture acceptance in a new, owned complete Mac application."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import signal
import socket
import ssl
import subprocess
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def ports_available():
    for port in (18480, 18443, 18444):
        with socket.socket() as handle:
            handle.bind(("127.0.0.1", port))


def https_assertions(archive, ready):
    context = ssl.create_default_context(cafile=str(archive / "https-cert.pem"))
    root = "https://localhost:18443/services/wire/v1"
    calls = []

    def call(path, body=None, auth=True):
        headers = {"Content-Type": "application/json"}
        if auth:
            headers["Authorization"] = "Basic " + base64.b64encode(b"admin:admin").decode()
        request = urllib.request.Request(root + path, headers=headers,
                                        data=json.dumps(body).encode() if body is not None else None,
                                        method="POST" if body is not None else "GET")
        try:
            with urllib.request.urlopen(request, context=context, timeout=10) as response:
                status, raw = response.status, response.read()
        except urllib.error.HTTPError as error:
            status, raw = error.code, error.read()
        record = {"method": request.method, "path": path, "authenticated": auth, "status": status,
                  "body": raw.decode(errors="replace")}
        calls.append(record)
        (archive / "https-calls.json").write_text(json.dumps(calls, ensure_ascii=False, indent=2) + "\n")
        assert status == (200 if auth else 401), record
        return json.loads(raw) if auth else None

    result = {"passed": False, "tls": "Owned localhost certificate and hostname validated"}
    try:
        readiness = []
        deadline = time.monotonic() + 10
        while True:
            request = urllib.request.Request(root + "/metadata/drivers/channelDescriptors")
            try:
                with urllib.request.urlopen(request, context=context, timeout=2) as response:
                    status = response.status
            except urllib.error.HTTPError as error:
                status = error.code
                error.close()
            readiness.append({"status": status})
            (archive / "https-route-readiness.json").write_text(json.dumps(readiness, indent=2) + "\n")
            if status == 401:
                break
            assert status in (404, 503) and time.monotonic() < deadline, readiness
            time.sleep(0.1)
        call("/metadata/drivers/channelDescriptors", auth=False)
        descriptor = call("/metadata/drivers/channelDescriptors/byPid", {"pids": [ready["driverPid"]]})
        entries = descriptor["driverChannelDescriptors"]
        assert len(entries) == 1 and entries[0]["pid"] == ready["driverPid"], entries
        attributes = {x["id"]: x for x in entries[0]["channelDescriptor"]}
        for kind, expected in ready["driverMinimumBounds"].items():
            assert str(attributes[kind + ".prop.min.max"]["min"]) == expected, attributes
        driver = call("/metadata/drivers/ocds/byFactoryPid", {"pids": ["org.eclipse.kura.util.test.driver.ChannelDescriptorTestDriver"]})
        assert "test.property" in json.dumps(driver) and "test value" in json.dumps(driver), driver
        emitter = call("/metadata/wireComponents/definitions/byFactoryPid", {"pids": ["org.eclipse.kura.util.wire.test.TestEmitterReceiver"]})
        definitions = emitter["wireComponentDefinitions"]
        assert len(definitions) == 1 and definitions[0]["factoryPid"] == "org.eclipse.kura.util.wire.test.TestEmitterReceiver", emitter
        assert definitions[0]["componentOCD"] == [], emitter
        for name in ("minInputPorts", "maxInputPorts", "defaultInputPorts", "minOutputPorts", "maxOutputPorts", "defaultOutputPorts"):
            assert definitions[0][name] == 1, emitter
        snapshot = call("/graph/snapshot")
        configuration = next(c for c in snapshot["configs"] if c["pid"] == "org.eclipse.kura.wire.graph.WireGraphService")
        graph = json.loads(configuration["properties"]["WireGraph"]["value"])
        assert {c["pid"] for c in graph["components"]} == {ready["emitterPid"], ready["receiverPid"]}, graph
        assert len(graph["wires"]) == 1, graph
        result.update(passed=True, calls=len(calls), minimumBoundsChecked=8, graphComponents=2, graphWires=1)
    except Exception as error:
        result["error"] = repr(error)
    (archive / "https-result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("runtime", "template-profile", "archive", "java"):
        parser.add_argument("--" + name, type=Path, required=True)
    args = parser.parse_args()
    for name in vars(args):
        setattr(args, name, getattr(args, name).resolve())
    protected = Path.home() / ".kura-dev"
    if args.archive == protected or protected in args.archive.parents or args.archive == args.runtime or args.runtime in args.archive.parents:
        parser.error("Use a new acceptance archive outside personal profiles and runtime")
    args.archive.mkdir(parents=True, exist_ok=False)
    ports_available()
    module = Path(__file__).resolve().parent
    helper = args.archive / "fixture-probe.jar"
    shutil.copy2(module / "target/kura-full-runtime-wire-fixture-acceptance-1.0.0-SNAPSHOT.jar", helper)
    home = args.archive / "profile"
    shutil.copytree(args.template_profile, home, ignore=shutil.ignore_patterns("logs", "tmp", "*-result.json"))
    (home / "logs").mkdir()
    (home / "tmp").mkdir()
    relocated = []
    for file in home.rglob("*"):
        if file.is_file() and file.suffix in (".xml", ".properties", ".json"):
            raw = file.read_bytes()
            if str(args.template_profile).encode() in raw:
                file.write_bytes(raw.replace(str(args.template_profile).encode(), str(home).encode()))
                relocated.append(str(file.relative_to(home)))
    (home / ".wire-fixture-acceptance-owned").write_text("Complete Mac wire fixture acceptance\n")
    (args.archive / "relocated-profile-files.json").write_text(json.dumps(relocated, indent=2) + "\n")
    # The stopped complete-runtime template has an empty HTTPS keystore. Provision
    # only the new owned copy, retaining its configured password and store format.
    bootstrap = ET.parse(home / "user/snapshots/snapshot_0.xml")
    https = next(c for c in bootstrap.iter() if c.attrib.get("pid") == "HttpsKeystore")
    properties = {p.attrib["name"]: p.find("{*}value").text for p in https.iter() if p.tag.endswith("property")}
    keystore = Path(properties["keystore.path"]).resolve()
    assert home in keystore.parents and keystore.is_file(), keystore
    keytool = args.java.parent / "keytool"
    environment = os.environ.copy()
    environment["KURA_ACCEPTANCE_KEYSTORE_PASSWORD"] = properties["keystore.password"]
    alias = "complete-mac-wire-fixture-https"
    key_commands = [
        [str(keytool), "-genkeypair", "-alias", alias, "-keyalg", "RSA", "-keysize", "2048", "-sigalg", "SHA256withRSA",
         "-validity", "2", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
         "-keystore", str(keystore), "-storepass:env", "KURA_ACCEPTANCE_KEYSTORE_PASSWORD", "-noprompt"],
        [str(keytool), "-exportcert", "-rfc", "-alias", alias, "-keystore", str(keystore),
         "-storepass:env", "KURA_ACCEPTANCE_KEYSTORE_PASSWORD", "-file", str(args.archive / "https-cert.pem")]]
    with (args.archive / "https-provision.log").open("w") as log:
        for command in key_commands:
            subprocess.run(command, env=environment, stdout=log, stderr=subprocess.STDOUT, check=True)
    configuration = args.archive / "configuration"
    configuration.mkdir()
    for file in (args.runtime / "configuration").iterdir():
        if file.is_file():
            text = file.read_text().replace(str(args.template_profile), str(home)).replace(str(args.runtime / "configuration"), str(configuration))
            if file.name == "kura.properties":
                text = re.sub(r"(?m)^kura.snapshots.encrypt=.*$", "kura.snapshots.encrypt=true", text)
            if file.name == "config.ini":
                text = re.sub(r"(?m)^osgi.bundles=(.*)$", lambda m: m.group(0) + ",reference:" + helper.as_uri() + "@6:start", text)
            (configuration / file.name).write_text(text)
    options = [x.replace(str(args.template_profile), str(home)).replace(str(args.runtime / "configuration"), str(configuration))
               for x in shlex.split((args.runtime / "jvm.args").read_text())]
    command = [str(args.java), *options, "-Dkura.acceptance.root=" + str(args.archive), "-jar", str(args.runtime / "launcher.jar"),
               "-configuration", str(configuration), "-install", str(args.runtime), "-console", "-consoleLog"]
    (args.archive / "command.json").write_text(json.dumps(command, indent=2) + "\n")
    started = time.monotonic()
    result_file = args.archive / "wire-probe-result.json"
    ready_file = args.archive / "wire-probe-ready.json"
    forced = False
    with (args.archive / "console.log").open("w") as log:
        app = subprocess.Popen(command, cwd=args.runtime, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            deadline = time.monotonic() + 120
            handled = False
            while app.poll() is None and not result_file.exists() and time.monotonic() < deadline:
                if ready_file.exists() and not handled:
                    handled = True
                    https_assertions(args.archive, json.loads(ready_file.read_text()))
                time.sleep(0.1)
            result = json.loads(result_file.read_text()) if result_file.exists() else {"passed": False, "error": "No result before JVM exit/120-second deadline"}
        finally:
            if app.poll() is None:
                os.killpg(app.pid, signal.SIGTERM)
                try:
                    app.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    forced = True
                    os.killpg(app.pid, signal.SIGKILL)
                    app.wait(timeout=5)
            app.stdin.close()
    ports_available()
    result.update(elapsedSeconds=round(time.monotonic() - started, 3), pid=app.pid, exitCode=app.returncode,
                  forcedShutdown=forced, portsReleased=True, helperSha256=sha(helper), httpsCertificateSha256=sha(args.archive / "https-cert.pem"))
    if forced:
        result.update(passed=False, error="Owned process required forced shutdown")
    result["sources"] = {str(p.relative_to(module)): sha(p) for p in module.rglob("*") if p.is_file() and "target" not in p.relative_to(module).parts}
    repository = module.parent.parent
    result["existingFixtureSources"] = {f: sha(repository / f) for f in (
        "bundles/org.eclipse.kura.rest.wire.provider/src/test/java/org/eclipse/kura/rest/wire/provider/test/ChannelDescriptorTestDriver.java",
        "bundles/org.eclipse.kura.rest.wire.provider/src/test/java/org/eclipse/kura/rest/wire/provider/test/RestGraphFixture.java")}
    result["logs"] = {f: sha(args.archive / f) for f in ("console.log", "profile/logs/kura.log")}
    (args.archive / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({k: result.get(k) for k in ("passed", "error", "elapsedSeconds", "forcedShutdown", "portsReleased")}), flush=True)
    raise SystemExit(0 if result["passed"] else 1)


if __name__ == "__main__":
    main()
