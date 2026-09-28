#!/usr/bin/env python3
"""Real RocketMQ regression: isolated containers, no host ports, no application data."""
import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "deploy/server/rocketmq"
IMAGE = "apache/rocketmq:5.3.4"


def run(*args, ok=True):
    result = subprocess.run(args, capture_output=True, text=True, timeout=180)
    if ok and result.returncode:
        raise AssertionError("Command failed: " + " ".join(args[:3]) + "\n" + result.stdout + result.stderr)
    return result


def main():
    token = "tradepass-mq-contract-" + uuid.uuid4().hex[:12]
    network, nameserver, broker = token, token + "-ns", token + "-broker"
    created = []
    with tempfile.TemporaryDirectory(prefix="tradepass-mq-contract-") as directory:
        folder = Path(directory)
        folder.chmod(0o755)
        conf = folder / "broker.conf"
        conf.write_text("\n".join([
            "brokerClusterName=DefaultCluster", "brokerName=tradepass-broker-a", "brokerId=0",
            "brokerRole=ASYNC_MASTER", "brokerIP1=127.0.0.1", "listenPort=10911",
            "namesrvAddr=nameserver:9876", "autoCreateTopicEnable=false", "autoCreateSubscriptionGroup=false",
            "mappedFileSizeCommitLog=67108864", "storePathRootDir=/tmp/store", "flushDiskType=SYNC_FLUSH"]))
        manifest = copy.deepcopy(json.loads((ASSETS / "resources.json").read_text()))
        manifest["advertisedAddress"] = "127.0.0.1:10911"
        # Deliberately require a second resource: ensure must not special-case the callback topic.
        manifest["resources"].append({**manifest["resources"][0], "id": "second-feature",
                                      "topic": "tradepass-second-events", "consumerGroup": "tradepass-second-consumer"})
        plan = folder / "resources.json"
        plan.write_text(json.dumps(manifest))

        def check(mode, success=True, value=None):
            plan.write_text(json.dumps(value or manifest))
            result = run("docker", "run", "--rm", "--memory", "160m", "--network", network,
                         "--mount", "type=bind,source=" + str(ASSETS) + ",target=/opt/resources,readonly",
                         "--mount", "type=bind,source=" + str(plan) + ",target=/tmp/resources.json,readonly",
                         IMAGE, "sh", "/opt/resources/resources.sh", mode, "/tmp/resources.json",
                         "nameserver:9876", "broker:10911", ok=False)
            if success != (result.returncode == 0):
                raise AssertionError(result.stdout + result.stderr)
            if success:
                assert "MQ_RESOURCES_OK" in result.stdout, result.stdout
            return result.stdout

        try:
            run("docker", "network", "create", "--internal", "--label", "tradepass.purpose=ci", network)
            for name, alias, command, heap, mounts in (
                (nameserver, "nameserver", ["sh", "mqnamesrv"], "-Xms32m -Xmx64m -Xmn16m", []),
                (broker, "broker", ["sh", "mqbroker", "-c", "/tmp/broker.conf"],
                 "-Xms128m -Xmx256m -Xmn64m -XX:MaxDirectMemorySize=128m",
                 ["--mount", "type=bind,source=" + str(conf) + ",target=/tmp/broker.conf,readonly"])):
                run("docker", "run", "-d", "--name", name, "--label", "tradepass.purpose=ci",
                    "--network", network, "--network-alias", alias, "--network-alias", "rocketmq-" + ("namesrv" if name == nameserver else "broker"), "--env", "JAVA_OPT_EXT=" + heap,
                    *mounts, IMAGE, *command)
                created.append(name)
            deadline = time.monotonic() + 90
            while "boot success" not in run("docker", "logs", broker).stdout:
                state = run("docker", "inspect", "--format", "{{.State.Status}}", broker).stdout.strip()
                if state == "exited" or time.monotonic() > deadline:
                    diagnostic = run("docker", "logs", broker)
                    run("docker", "cp", broker + ":/home/rocketmq/logs/rocketmqlogs/broker.log", str(folder / "broker.log"), ok=False)
                    extra = (folder / "broker.log").read_text() if (folder / "broker.log").exists() else ""
                    raise AssertionError("Isolated broker did not start\n" + diagnostic.stderr[-3000:] + extra[-2000:])
                time.sleep(1)
            output = check("audit", False)
            assert "ALL_TOPICS" in output and "ALL_CONSUMER_GROUPS" in output
            assert "contract-callback" in output and "second-feature" in output
            print("PASS: missing resources fail audit for every entry", flush=True)
            wrong = {**manifest, "brokerName": "wrong-broker"}
            check("ensure", False, wrong)
            output = check("ensure")
            assert output.count("CREATED topic ") == 2 and output.count("CREATED consumer group ") == 2
            print("PASS: separate init container provisions all resources despite loopback advertisement", flush=True)
            spec = importlib.util.spec_from_file_location("mq_resources", ROOT / "scripts/server/mq_resources.py")
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            module.execute("check", manifest, broker)
            print("PASS: host audit helper copies the runner, uses stdin, and verifies from the broker namespace", flush=True)
            output = check("ensure")
            assert "CREATED " not in output
            print("PASS: repeated ensure does not recreate resources", flush=True)
            extra = copy.deepcopy(manifest)
            extra["resources"].append({**extra["resources"][0], "id": "unrelated",
                                       "topic": "keep-unrelated-events", "consumerGroup": "keep-unrelated-consumer"})
            check("ensure", value=extra)
            output = check("audit")
            assert "keep-unrelated-events" in output and "keep-unrelated-consumer" in output
            print("PASS: inventory lists and preserves unrelated resources", flush=True)
            # Explicitly disable an existing group. Typed readback, not mqadmin's exit code, proves drift.
            run("docker", "exec", "-e", "JAVA_OPT_EXT=-Xms16m -Xmx64m", broker,
                "sh", "mqadmin", "updateSubGroup", "-n", "nameserver:9876", "-b", "127.0.0.1:10911",
                "-g", manifest["resources"][1]["consumerGroup"], "-s", "false")
            assert "DRIFT second-feature" in check("ensure", False)
            assert "DRIFT second-feature" in check("audit", False)
            print("PASS: disabled consumer group blocks ensure; existing config is not overwritten", flush=True)
            print("RocketMQ resource contract integration checks passed", flush=True)
        finally:
            for name in reversed(created):
                run("docker", "rm", "-f", "-v", name, ok=False)
            run("docker", "network", "rm", network, ok=False)


if __name__ == "__main__":
    main()
