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


def environment_value(environment, property_name, default=None):
    # Direct Spring properties override YAML; ROCKETMQ_* are only YAML placeholders.
    names = {property_name, property_name.upper().replace(".", "_").replace("-", "_"),
             property_name.upper().replace(".", "_").replace("-", "")}
    found = [environment[name] for name in names if name in environment]
    if len(set(found)) > 1:
        raise ValueError("容器中存在冲突的属性覆盖：" + property_name)
    return found[0] if found else default


def resolve_plan(catalog, common, business, environment=None, legacy_profile=False):
    """Resolve supported config sources; reject ambiguous legacy profile/Nacos overlaps."""
    environment = environment or {}
    missing = object()

    def remote_value(key):
        return get_path(business, key, get_path(common, key, missing))

    def expand(value):
        # Resolve Nacos YAML placeholders from this container, never the operator's shell/.env.
        if not isinstance(value, str):
            return value
        pattern = re.compile(r"\$\{([^:{}]+)(?::([^{}]*))?}")
        for _ in range(10):
            if "${" not in value:
                return value
            def replace(match):
                key, fallback = match.groups()
                replacement = environment_value(environment, key, remote_value(key))
                if replacement is missing:
                    if fallback is None:
                        raise ValueError("Nacos MQ 配置含无法解析的占位符")
                    replacement = fallback
                return str(replacement)
            updated = pattern.sub(replace, value)
            if updated == value:
                break
            value = updated
        raise ValueError("Nacos MQ 配置含循环或不支持的占位符")

    def value(key, fallback=None, alias=None):
        direct = environment_value(environment, key, missing)
        if direct is not missing:
            return direct
        remote = remote_value(key)
        local = environment.get(alias, fallback) if alias else fallback
        if remote is not missing:
            resolved = expand(remote)
            # The old nacos profile imports remote data alongside application-messaging.yml.
            # Nacos config.preference and profile ordering can affect which wins. Do not guess
            # when the two sources disagree; direct Spring environment overrides are unambiguous.
            if legacy_profile:
                left, right = str(resolved), str(local)
                if key.endswith(".enabled"):
                    left, right = left.lower(), right.lower()
                if left != right:
                    raise ValueError("旧部署的 Nacos 与 messaging profile 配置冲突：" + key
                                     + "；无法确认生效值，未初始化资源（未输出配置值）")
            return resolved
        return local

    if str(value(PREFIX + "enabled", True)).lower() != "true":
        raise ValueError("Core 业务必须启用 RocketMQ，配置为关闭时不能通过检查")
    if value(PREFIX + "name-server", alias="ROCKETMQ_NAME_SERVER") != "127.0.0.1:9876":
        raise ValueError("此检查适用于 core 本机 NameServer 127.0.0.1:9876；拒绝检查错误集群")
    plan = copy.deepcopy(catalog)
    plan["advertisedAddress"] = "127.0.0.1:10911"
    for resource in plan["resources"]:
        for name in ("topic", "consumerGroup"):
            prop = resource[name + "Property"]
            # Backward compatible catalog entries derive the application-messaging aliases.
            alias = resource.get(name + "Env") or "ROCKETMQ_" + (prop[len(PREFIX):] if prop.startswith(PREFIX) else prop).upper().replace("-", "_")
            resource[name] = value(prop, resource[name], alias)
            if not isinstance(resource[name], str) or not re.fullmatch(r"[A-Za-z0-9_-][A-Za-z0-9_%-]{0,126}", resource[name]):
                raise ValueError(resource["id"] + " 的 Topic/消费组配置无效或包含未解析占位符")
    plan["accessKey"] = value(PREFIX + "access-key", "", "ROCKETMQ_ACCESS_KEY")
    plan["secretKey"] = value(PREFIX + "secret-key", "", "ROCKETMQ_SECRET_KEY")
    if any(not isinstance(plan[key], str) for key in ("accessKey", "secretKey")):
        raise ValueError("RocketMQ ACL 配置必须为字符串")
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


def runtime_source(info):
    """Identify supported running-container layouts without requiring a migration/restart."""
    if info["HostConfig"].get("NetworkMode") != "host":
        raise ValueError("仅支持 core host 网络部署")
    config = info["Config"]
    environment = dict(entry.split("=", 1) for entry in config.get("Env", []))
    for key in ("SPRING_APPLICATION_JSON", "SPRING_CONFIG_IMPORT", "SPRING_CONFIG_LOCATION", "SPRING_PROFILES_INCLUDE"):
        if environment.get(key):
            raise ValueError("容器使用尚不支持的配置覆盖：" + key + "；已停止，未按默认值初始化")
    name = environment.get("SPRING_APPLICATION_NAME", "tradepass-business")
    if name != "tradepass-business":
        raise ValueError("业务容器 application name 与预期不一致")
    # Shell/JVM overrides need an explicit parser; never silently initialize the wrong resources.
    arguments = json.dumps([config.get("Cmd"), config.get("Entrypoint"),
                            *[environment.get(key, "") for key in ("JAVA_OPTS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")]])
    if any(prop in arguments.lower() for prop in ("tradepass.messaging", "spring.cloud.nacos", "spring.config.",
                                                 "spring.application.name", "spring.profiles.", "nacos_", "rocketmq_")):
        raise ValueError("启动参数包含未解析的 MQ/Nacos 配置覆盖；已停止，未按默认值初始化")
    mounts = [item for item in info.get("Mounts", []) if item["Destination"] == "/app/nacos-bootstrap.yml"]
    location = environment.get("SPRING_CONFIG_ADDITIONAL_LOCATION", "")
    if location:
        if location != "file:/app/nacos-bootstrap.yml" or len(mounts) != 1 or mounts[0].get("Type") != "bind":
            raise ValueError("无法定位 SPRING_CONFIG_ADDITIONAL_LOCATION 对应的 bootstrap 挂载")
        return Path(mounts[0]["Source"]), environment, True
    if mounts:
        raise ValueError("存在 bootstrap 挂载但未启用其配置位置；无法确认实际配置源")
    profiles = {profile.strip() for profile in environment.get("SPRING_PROFILES_ACTIVE", "").split(",")}
    if not {"core", "messaging"} <= profiles:
        raise ValueError("未确认业务容器启用 core、messaging profile；已停止，未按默认值初始化")
    return None, environment, "nacos" in profiles


def nacos_options(bootstrap, environment):
    if bootstrap is not None:
        boot = yaml.safe_load(bootstrap.read_text())
        options = get_path(boot, "spring.cloud.nacos.config")
        if not isinstance(options, dict):
            raise ValueError("bootstrap 缺少 Nacos config 配置")
        # A direct Spring environment override also overrides the mounted YAML.
        options = {key: environment_value(environment, "spring.cloud.nacos.config." + key, value)
                   for key, value in options.items()}
        imports = get_path(boot, "spring.config.import")
    else:
        defaults = {"server-addr": "127.0.0.1:8848", "namespace": "", "group": "TRADEPASS", "username": "", "password": ""}
        aliases = {"server-addr": "NACOS_SERVER_ADDR", "namespace": "NACOS_NAMESPACE", "group": "NACOS_GROUP",
                   "username": "NACOS_USERNAME", "password": "NACOS_PASSWORD"}
        options = {key: environment_value(environment, "spring.cloud.nacos.config." + key,
                                          environment.get(aliases[key], value)) for key, value in defaults.items()}
        options["enabled"] = environment_value(environment, "spring.cloud.nacos.config.enabled", True)
        # The packaged application's import query uses NACOS_GROUP, independently of config.group.
        group = environment.get("NACOS_GROUP", "TRADEPASS")
        imports = ["nacos:" + name + ".yaml?group=" + group + "&refreshEnabled=false"
                   for name in ("tradepass-common", "${spring.application.name}")]
    if str(options.get("enabled")).lower() != "true" or not re.fullmatch(r"(?:127\.0\.0\.1|localhost):[0-9]+", options.get("server-addr", "")):
        raise ValueError("无效的本机 Nacos 配置")
    expected_imports = ["nacos:" + name + ".yaml?group=" + options.get("group", "") + "&refreshEnabled=false"
                        for name in ("tradepass-common", "${spring.application.name}")]
    if imports != expected_imports:
        raise ValueError("Nacos import 列表或分组与 core 配置不一致；拒绝按错误的配置源初始化")
    return options


def read_nacos(options):
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
    return configs


def configured_plan(bootstrap=None):
    environment = {}
    uses_nacos = True
    if bootstrap is None:
        info = json.loads(run("docker", "inspect", "tradepass-core-business-1"))[0]
        bootstrap, environment, uses_nacos = runtime_source(info)
    configs = read_nacos(nacos_options(bootstrap, environment)) if uses_nacos else ({}, {})
    plan = resolve_plan(json.loads((ASSETS / "resources.json").read_text()), *configs, environment,
                        legacy_profile=uses_nacos and bootstrap is None)
    source = "挂载 bootstrap + Nacos" if bootstrap else "容器环境变量 + Nacos" if uses_nacos else "容器环境变量"
    print("配置来源：" + source, flush=True)
    return plan


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
    parser.add_argument("--bootstrap", type=Path, help="首次部署时的 Nacos bootstrap；现有部署自动识别容器环境变量或挂载配置")
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
