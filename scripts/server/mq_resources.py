#!/usr/bin/env python3
"""Audit or provision the complete versioned RocketMQ resource contract. No messages/offsets/DB writes."""
import argparse
import copy
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import sys

import yaml

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "deploy/server/rocketmq"
# The SSH publisher ships the same files in a flat, temporary bundle.
if not ASSETS.is_dir():
    ASSETS = Path(__file__).resolve().parent / "rocketmq"
CONFIGURATOR = Path(__file__).with_name("configure-core-nacos.py")
PREFIX = "tradepass.messaging.rocketmq."


def get_path(document, path, default=None):
    # Spring accepts both dotted and nested YAML keys, including mixed forms.
    missing = object()
    found = []
    if isinstance(document, dict):
        for key, value in document.items():
            if path == key:
                found.append(value)
            elif isinstance(key, str) and path.startswith(key + "."):
                match = get_path(value, path[len(key) + 1:], missing)
                if match is not missing:
                    found.append(match)
    if len(found) > 1:
        raise ValueError("配置包含重复属性：" + path)
    return found[0] if found else default


def resolve_plan(catalog, common, business):
    """Resolve the native Nacos keys used by core, never from an old .env file."""
    def value(key, fallback=None):
        return get_path(business, key, get_path(common, key, fallback))
    if value(PREFIX + "enabled", True) is not True:
        raise ValueError("Core 业务必须启用 RocketMQ，配置为关闭时不能通过检查")
    if value(PREFIX + "name-server") != "127.0.0.1:9876":
        raise ValueError("此检查适用于 core 本机 NameServer 127.0.0.1:9876；拒绝检查错误集群")
    plan = copy.deepcopy(catalog)
    plan["advertisedAddress"] = "127.0.0.1:10911"
    for resource in plan["resources"]:
        for name in ("topic", "consumerGroup"):
            resource[name] = value(resource[name + "Property"], resource[name])
            if not isinstance(resource[name], str) or not re.fullmatch(r"[A-Za-z0-9_-][A-Za-z0-9_%-]{0,126}", resource[name]):
                raise ValueError(resource["id"] + " 的 Topic/消费组配置无效或包含未解析占位符")
    plan["accessKey"] = value(PREFIX + "access-key", "")
    plan["secretKey"] = value(PREFIX + "secret-key", "")
    if any(not isinstance(plan[key], str) or "${" in plan[key] for key in ("accessKey", "secretKey")):
        raise ValueError("RocketMQ ACL 配置包含未解析占位符")
    if bool(plan["accessKey"]) != bool(plan["secretKey"]):
        raise ValueError("RocketMQ ACL 必须同时配置 access-key 和 secret-key")
    return plan


def run(*command, input=None):
    try:
        result = subprocess.run(command, input=input, capture_output=True, text=True, timeout=180)
    except (OSError, subprocess.TimeoutExpired):
        raise ValueError("无法执行 Docker 操作或操作超时") from None
    if result.returncode:
        raise ValueError("Docker 操作失败：" + " ".join(command[:2]) + "；未输出私有配置")
    return result.stdout.strip()


def configured_plan(bootstrap=None):
    if bootstrap is None:
        info = json.loads(run("docker", "inspect", "tradepass-core-business-1"))[0]
        if info["HostConfig"].get("NetworkMode") != "host":
            raise ValueError("仅支持 core host 网络部署")
        mounts = [item["Source"] for item in info["Mounts"] if item["Destination"] == "/app/nacos-bootstrap.yml"]
        if len(mounts) != 1:
            raise ValueError("未找到业务容器的 Nacos bootstrap；请使用 --bootstrap 明确指定启动文件")
        bootstrap = Path(mounts[0])
        # Native Nacos is authoritative in this deployment. Refuse ambiguous overrides.
        config = info["Config"]
        for entry in config.get("Env", []):
            key, _, value = entry.partition("=")
            if (key.startswith(("ROCKETMQ_", "TRADEPASS_MESSAGING_"))
                    or key == "SPRING_APPLICATION_JSON" or key.startswith("SPRING_CLOUD_NACOS_")
                    or key in ("SPRING_CONFIG_IMPORT", "SPRING_CONFIG_LOCATION")
                    or (key == "SPRING_CONFIG_ADDITIONAL_LOCATION" and value != "file:/app/nacos-bootstrap.yml")
                    or (key == "SPRING_APPLICATION_NAME" and value != "tradepass-business")
                    or any(prop in value for prop in ("tradepass.messaging", "spring.cloud.nacos", "spring.config.import", "spring.config.location"))):
                raise ValueError("业务容器存在额外 MQ 配置覆盖，请先统一为 Nacos 原生配置")
        if any(prop in json.dumps([config.get("Cmd"), config.get("Entrypoint")]) for prop in (
                "tradepass.messaging", "spring.cloud.nacos", "spring.config.import", "spring.config.location", "spring.application.name")):
            raise ValueError("业务启动参数覆盖 MQ 配置，请先统一为 Nacos 原生配置")
    boot = yaml.safe_load(bootstrap.read_text())
    options = get_path(boot, "spring.cloud.nacos.config")
    if not isinstance(options, dict) or options.get("enabled") is not True or not re.fullmatch(r"(?:127\.0\.0\.1|localhost):[0-9]+", options.get("server-addr", "")):
        raise ValueError("无效的本机 Nacos bootstrap")
    expected_imports = ["nacos:" + name + ".yaml?group=" + options.get("group", "") + "&refreshEnabled=false"
                        for name in ("tradepass-common", "${spring.application.name}")]
    if get_path(boot, "spring.config.import") != expected_imports:
        raise ValueError("Nacos import 列表与 core 配置不一致；拒绝按错误的配置源初始化")
    spec = importlib.util.spec_from_file_location("mq_nacos_reader", CONFIGURATOR)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    values = {"NACOS_" + key: options.get(prop, "") for key, prop in (
        ("SERVER_ADDR", "server-addr"), ("GROUP", "group"), ("NAMESPACE", "namespace"),
        ("USERNAME", "username"), ("PASSWORD", "password"))}
    try:
        client = module.Nacos(values)
        configs = [yaml.safe_load(client.read(role) or "null") for role in ("common", "business")]
    except Exception:
        raise ValueError("读取 Nacos 失败；未按默认值继续，未输出凭据") from None
    if not all(isinstance(config, dict) for config in configs):
        raise ValueError("Nacos common/business 配置缺失")
    return resolve_plan(json.loads((ASSETS / "resources.json").read_text()), *configs)


def verify_mapping(broker):
    # Direct access is inside the broker. Prove its advertised host ports refer to this container.
    info = json.loads(run("docker", "inspect", broker))[0]
    mappings = info["NetworkSettings"].get("Ports", {}).get("10911/tcp") or []
    if not any(item["HostIp"] == "127.0.0.1" and item["HostPort"] == "10911" for item in mappings):
        raise ValueError("Broker 未映射预期的本机 10911 端口，拒绝检查错误实例")
    nameserver = json.loads(run("docker", "inspect", "tradepass-infra-static-rocketmq-namesrv-1"))[0]
    mappings = nameserver["NetworkSettings"].get("Ports", {}).get("9876/tcp") or []
    if not any(item["HostIp"] == "127.0.0.1" and item["HostPort"] == "9876" for item in mappings):
        raise ValueError("NameServer 未映射预期的本机 9876 端口")
    if not set(info["NetworkSettings"]["Networks"]) & set(nameserver["NetworkSettings"]["Networks"]):
        raise ValueError("Broker 和 NameServer 不在同一网络")


def execute(mode, plan, broker):
    directory = run("docker", "exec", broker, "mktemp", "-d", "/tmp/tradepass-mq-resources.XXXXXX")
    if not re.fullmatch(r"/tmp/tradepass-mq-resources\.[A-Za-z0-9]+", directory):
        raise ValueError("无法创建检查程序的临时目录")
    try:
        for name in ("resources.sh", "ResourceAdmin.java"):
            run("docker", "cp", str(ASSETS / name), broker + ":" + directory + "/" + name)
        result = subprocess.run(["docker", "exec", "-i", broker, "sh", directory + "/resources.sh", mode,
                                 "-", "rocketmq-namesrv:9876", "127.0.0.1:10911"],
                                input=json.dumps(plan), text=True, capture_output=True, timeout=180)
        # Only emit the helper's bounded, secret-free report. JVM/library logs stay private.
        for line in result.stdout.splitlines():
            if line.startswith(("Broker:", "ALL_", "CREATED ", "DRIFT ", "MQ_RESOURCE_ERROR ", "MQ_RESOURCES_")) or " consumerGroup=" in line:
                print(line, flush=True)
        if result.returncode:
            raise ValueError("MQ 资源校验失败（退出码 " + str(result.returncode) + "）；已中止后续发布/启动")
        if "MQ_RESOURCES_OK" not in result.stdout.splitlines():
            raise ValueError("MQ 检查未返回成功凭据；拒绝将退出码 0 视为资源就绪")
    finally:
        run("docker", "exec", broker, "rm", "-rf", "--", directory)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("audit", "check", "ensure"), nargs="?", default="audit")
    parser.add_argument("--bootstrap", type=Path, help="首次部署时的 Nacos bootstrap；通常自动从业务容器挂载定位")
    parser.add_argument("--broker", default="tradepass-infra-static-rocketmq-broker-1")
    args = parser.parse_args(argv)
    plan = configured_plan(args.bootstrap)
    verify_mapping(args.broker)
    execute(args.mode, plan, args.broker)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, OSError, KeyError, yaml.YAMLError, subprocess.TimeoutExpired) as error:
        # File/network/config errors must not leak Nacos documents or secrets.
        message = str(error) if type(error) is ValueError else type(error).__name__
        print("错误：" + message, file=sys.stderr)
        sys.exit(1)
