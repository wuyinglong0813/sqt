#!/usr/bin/env python3
"""Move application settings to Nacos once; generate Docker startup YAML without dotenv."""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import urllib.error
import urllib.parse
import urllib.request

import yaml

ROOT = Path(__file__).resolve().parents[2]
SERVER = ROOT / "deploy/server"
# (dotenv source, native Spring property, fallback). No OS env bridge at runtime.
COMMON = [
    ("TRADEPASS_INTERNAL_KEY", "tradepass.services.internal-key", ""),
    ("TRADEPASS_IDS_DATACENTER_ID", "tradepass.ids.datacenter-id", "2"),
    ("TRADEPASS_REDIS_ENABLED", "tradepass.redis.enabled", "false"),
    ("REDIS_PASSWORD", "spring.data.redis.password", ""),
    ("REDIS_PUBLISH_PORT", "spring.data.redis.port", "6379"),
    ("WECHAT_APP_ID", "wechat.app-id", ""),
    ("WECHAT_APP_SECRET", "wechat.app-secret", ""),
    ("FADADA_ENABLED", "tradepass.fadada.enabled", "false"),
    ("FADADA_APP_ID", "tradepass.fadada.app-id", ""),
    ("FADADA_APP_SECRET", "tradepass.fadada.app-secret", ""),
    ("FADADA_SERVER_URL", "tradepass.fadada.server-url", "https://api.fadada.com/api/v5"),
    ("FADADA_CALLBACK_URL", "tradepass.fadada.callback-url", ""),
]
BUSINESS = [
    ("ROCKETMQ_CALLBACK_TOPIC", "tradepass.messaging.rocketmq.callback-topic", "tradepass-callback-events"),
    ("ROCKETMQ_ACCESS_KEY", "tradepass.messaging.rocketmq.access-key", ""),
    ("ROCKETMQ_SECRET_KEY", "tradepass.messaging.rocketmq.secret-key", ""),
    ("TRADEPASS_STORAGE_PROVIDER", "tradepass.storage.provider", "cloudbase-cos"),
    ("TRADEPASS_STORAGE_ENABLED", "tradepass.storage.enabled", "false"),
    ("TRADEPASS_STORAGE_REQUIRED", "tradepass.storage.required", "false"),
    ("CLOUDBASE_STORAGE_BUCKET", "tradepass.storage.bucket", ""),
    ("CLOUDBASE_STORAGE_REGION", "tradepass.storage.region", "ap-shanghai"),
    ("OSS_ENDPOINT", "tradepass.storage.oss.endpoint", ""),
    ("OSS_REGION", "tradepass.storage.oss.region", ""),
    ("OSS_BUCKET", "tradepass.storage.oss.bucket", ""),
    ("OSS_ACCESS_KEY_ID", "tradepass.storage.oss.access-key-id", ""),
    ("OSS_ACCESS_KEY_SECRET", "tradepass.storage.oss.access-key-secret", ""),
    ("OSS_SESSION_TOKEN", "tradepass.storage.oss.session-token", ""),
    ("TRADEPASS_LEGACY_COS_BUCKET", "tradepass.storage.oss.legacy-cos-bucket", ""),
    ("TRADEPASS_LEGACY_COS_REGION", "tradepass.storage.oss.legacy-cos-region", "ap-shanghai"),
    ("TRADEPASS_LEGACY_COS_KEY_PREFIX", "tradepass.storage.oss.legacy-cos-key-prefix", "tradepass"),
    ("TRADEPASS_LEGACY_COS_SECRET_ID", "tradepass.storage.oss.legacy-cos-secret-id", ""),
    ("TRADEPASS_LEGACY_COS_SECRET_KEY", "tradepass.storage.oss.legacy-cos-secret-key", ""),
    ("TRADEPASS_LEGACY_COS_SESSION_TOKEN", "tradepass.storage.oss.legacy-cos-session-token", ""),
]
DATABASE = [(role.upper() + suffix, "spring.datasource." + prop, "")
            for role in ("identity", "business")
            for suffix, prop in (("_DATABASE_URL", "url"), ("_DB_USERNAME", "username"), ("_DB_PASSWORD", "password"))]
BOOTSTRAP = [("NACOS_SERVER_ADDR", "", "127.0.0.1:8848"), ("NACOS_GROUP", "", "TRADEPASS_CORE"),
             ("NACOS_NAMESPACE", "", ""), ("NACOS_USERNAME", "", "nacos"), ("NACOS_PASSWORD", "", "")]


def compose_config(env_file, filename=None, document=None):
    document = copy.deepcopy(document if document is not None else yaml.safe_load(filename.read_text()))
    probe = "tradepass-config-dollar-probe"
    document["services"][probe] = {"image": "busybox", "environment": {"DOLLAR": "$$"}}
    command = ["docker", "compose", "--env-file", str(env_file), "--project-directory", str(SERVER),
               "-f", "-", "config", "--format", "json"]
    # Resolve only against the selected private file, not a stale `source .env` shell.
    blocked = {item[0] for item in COMMON + BUSINESS + DATABASE + BOOTSTRAP}
    blocked.update(re.findall(r"^([A-Z][A-Z0-9_]*)=", (SERVER / ".env.core.example").read_text(), re.M))
    environment = {key: value for key, value in os.environ.items()
                   if key not in blocked and not key.startswith("COMPOSE_")}
    result = subprocess.run(command, input=json.dumps(document),
                            capture_output=True, text=True, env=environment, timeout=60)
    if result.returncode:
        raise ValueError("Docker Compose 配置解析失败，请检查私有文件和 Docker Compose 版本（未输出配置或密钥）")
    parsed = json.loads(result.stdout)
    dollar = parsed["services"].pop(probe)["environment"]["DOLLAR"]
    if dollar not in ("$", "$$"):
        raise ValueError("无法识别 Docker Compose 的美元符号转义格式")
    # Older Compose releases also escape dollars when serializing JSON.
    return transform_strings(parsed, lambda value: value.replace("$$", "$")) if dollar == "$$" else parsed


def transform_strings(value, transform):
    if isinstance(value, str):
        return transform(value)
    if isinstance(value, list):
        return [transform_strings(item, transform) for item in value]
    if isinstance(value, dict):
        return {key: transform_strings(item, transform) for key, item in value.items()}
    return value


def read_source(env_file):
    if env_file.is_symlink() or not env_file.is_file() or env_file.stat().st_mode & 0o077:
        raise ValueError("一次性导入文件必须是权限 600 的普通文件")
    values = {key: "${" + key + ":-" + default + "}" for key, _, default in COMMON + BUSINESS + DATABASE + BOOTSTRAP}
    document = {"name": "tradepass-config-read", "services": {"read": {"image": "busybox", "environment": values}}}
    # `config` only parses; no image is pulled and no container is created.
    return compose_config(env_file, document=document)["services"]["read"]["environment"]


def set_path(document, path, value):
    parts = path.split(".")
    for part in parts[:-1]:
        document = document.setdefault(part, {})
    document[parts[-1]] = value


def get_path(document, path):
    for part in path.split("."):
        if not isinstance(document, dict) or part not in document:
            return None
        document = document[part]
    return document


def document_for(entries, values):
    result = {}
    for key, path, default in entries:
        value = values.get(key, default)
        if path.endswith((".enabled", ".required")):
            if value not in ("true", "false"):
                raise ValueError(key + " 必须为 true 或 false")
            value = value == "true"
        elif path.endswith((".port", ".datacenter-id")):
            try:
                value = int(value)
            except ValueError:
                raise ValueError(key + " 必须为整数") from None
        set_path(result, path, value)
    return result


def documents(values):
    result = {"common": document_for(COMMON, values), "gateway": {}}
    set_path(result["common"], "spring.data.redis.host", "127.0.0.1")
    set_path(result["common"], "management.health.redis.enabled", get_path(result["common"], "tradepass.redis.enabled"))
    for role in ("identity", "business"):
        result[role] = document_for([entry for entry in DATABASE if entry[0].startswith(role.upper())], values)
        set_path(result[role], "spring.datasource.hikari.maximum-pool-size", 3)
        set_path(result[role], "spring.datasource.hikari.minimum-idle", 0)
        for name in ("identity", "contract", "trade", "settlement", "file"):
            set_path(result[role], "tradepass.services." + name + "-url", "")
    result["business"] = merge_missing(result["business"], document_for(BUSINESS, values))
    set_path(result["business"], "tradepass.messaging.rocketmq.enabled", True)
    set_path(result["business"], "tradepass.messaging.rocketmq.name-server", "127.0.0.1:9876")
    for name in ("identity", "contract", "trade", "settlement", "file"):
        target = "tradepass-identity" if name == "identity" else "tradepass-business"
        set_path(result["gateway"], "tradepass.services." + name + "-url", "lb://" + target)
    return result


def merge_missing(existing, defaults):
    """The current Nacos value wins. The old file supplies only missing keys."""
    result = copy.deepcopy(existing)
    for key, value in defaults.items():
        if key not in result:
            result[key] = copy.deepcopy(value)
        elif isinstance(value, dict):
            if not isinstance(result[key], dict):
                raise ValueError("Nacos 配置结构不兼容，请先检查 YAML 层级")
            result[key] = merge_missing(result[key], value)
    return result


def validate_documents(configs):
    common = configs["common"]
    key = get_path(common, "tradepass.services.internal-key")
    if not isinstance(key, str) or len(key) < 32:
        raise ValueError("Nacos 内部调用密钥缺失或过短")
    dc = get_path(common, "tradepass.ids.datacenter-id")
    if type(dc) is not int or not 0 <= dc <= 31:
        raise ValueError("Nacos datacenter-id 必须在 0..31")
    for role in ("identity", "business"):
        url = get_path(configs[role], "spring.datasource.url")
        if not isinstance(url, str) or not re.search(r"_" + role + r"(?:\?|$)", url):
            raise ValueError(role + " 数据库必须使用对应的独立库")
        for prop in ("username", "password"):
            if not get_path(configs[role], "spring.datasource." + prop):
                raise ValueError(role + " 数据库凭据缺失")


def bootstrap(values):
    address = values["NACOS_SERVER_ADDR"]
    if not re.fullmatch(r"(?:127\.0\.0\.1|localhost):[0-9]+", address):
        raise ValueError("此单机迁移工具只连接本机 Nacos；通过 SSH 在服务器运行")
    group = values["NACOS_GROUP"]
    if not re.fullmatch(r"[A-Za-z0-9_.-]+", group):
        raise ValueError("NACOS_GROUP 格式不支持")
    if not values["NACOS_PASSWORD"] or values["NACOS_PASSWORD"] == "nacos":
        raise ValueError("请先为 Nacos 设置私有密码")
    options = {"enabled": True, "server-addr": address, "namespace": values["NACOS_NAMESPACE"],
               "group": group, "username": values["NACOS_USERNAME"], "password": values["NACOS_PASSWORD"]}
    imports = ["nacos:" + data_id + ".yaml?group=" + group + "&refreshEnabled=false"
               for data_id in ("tradepass-common", "${spring.application.name}")]
    return {"spring": {"config": {"import": imports}, "cloud": {"nacos": {
        "config": options, "discovery": {**options, "ip": "127.0.0.1"}}}}}


class Nacos:
    def __init__(self, values):
        self.base = "http://" + values["NACOS_SERVER_ADDR"] + "/nacos/v1"
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        self.params = {"group": values["NACOS_GROUP"], "tenant": values["NACOS_NAMESPACE"]}
        login = self.request("/auth/login", {"username": values["NACOS_USERNAME"], "password": values["NACOS_PASSWORD"]}, post=True)
        self.params["accessToken"] = json.loads(login)["accessToken"]

    def request(self, path, params, post=False, headers=None):
        encoded = urllib.parse.urlencode(params).encode()
        req = urllib.request.Request(self.base + path + ("" if post else "?" + encoded.decode()),
                                     data=encoded if post else None, headers=headers or {})
        with self.opener.open(req, timeout=15) as response:
            return response.read().decode("utf-8")

    def read(self, role):
        try:
            return self.request("/cs/configs", {**self.params, "dataId": "tradepass-" + role + ".yaml"})
        except urllib.error.HTTPError as error:
            if error.code == 404:
                return None
            raise

    def publish(self, role, content, previous):
        # Nacos 2.3.2 expects CAS in an HTTP header, not in form parameters.
        headers = {"casMd5": hashlib.md5(previous.encode()).hexdigest()} if previous is not None else {}
        if self.read(role) != previous:
            raise ValueError("Nacos 配置在导入期间发生变化，已停止发布")
        result = self.request("/cs/configs", {**self.params, "dataId": "tradepass-" + role + ".yaml",
                                               "content": content, "type": "yaml"}, post=True, headers=headers)
        if result.strip() != "true" or self.read(role) != content:
            raise ValueError("Nacos 未确认配置发布，未生成新的启动文件")


def private_write(path, content):
    if path.is_symlink():
        raise ValueError("私有配置目标不能是符号链接")
    fd, temporary = tempfile.mkstemp(dir=str(path.parent), prefix=".config-")
    try:
        with os.fdopen(fd, "w") as out:
            os.fchmod(out.fileno(), 0o600)
            out.write(content)
        os.replace(temporary, str(path))
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def compose_literal(value):
    # Resolved Compose JSON uses literal values. Escape dollars before serializing
    # back to a Compose file so secrets are not interpolated on the next run.
    return transform_strings(value, lambda item: item.replace("$", "$$"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-env", type=Path, default=SERVER / ".env.core", help="仅用于一次性导入，不作为应用运行配置")
    parser.add_argument("--publish", action="store_true", help="发布缺失的 Nacos 配置项并生成启动 YAML")
    args = parser.parse_args()
    values = read_source(args.source_env.absolute())
    boot = bootstrap(values)
    client = Nacos(values)
    defaults = documents(values)
    previous, merged = {}, {}
    for role in defaults:
        previous[role] = client.read(role)
        existing = yaml.safe_load(previous[role]) if previous[role] else {}
        if not isinstance(existing, dict):
            raise ValueError("Nacos 配置必须是 YAML 对象")
        merged[role] = merge_missing(existing, defaults[role])
    validate_documents(merged)
    # Validate and resolve all Docker definitions before publishing anything.
    runtime = {name: compose_config(args.source_env.absolute(), SERVER / source) for name, source in (
        ("core", "yudao.core.compose.yml"), ("infra", "infra.core.compose.yml"), ("edge", "edge.core.compose.yml"))}
    if not args.publish:
        print("已完成检查：将保留 Nacos 已有值，补齐缺失项；加 --publish 执行。未输出密钥。")
        return
    for role, config in merged.items():
        content = yaml.safe_dump(config, allow_unicode=True, sort_keys=False)
        if yaml.safe_load(previous[role] or "{}") != config:
            client.publish(role, content, previous[role])
        print("Nacos 就绪：tradepass-" + role + ".yaml")
    output = SERVER / ".runtime"
    for path in (output, output / "nacos"):
        if path.is_symlink():
            raise ValueError("私有目录不能是符号链接")
        path.mkdir(mode=0o700, exist_ok=True)
        path.chmod(0o700)
    private_write(output / "nacos/bootstrap.yml", yaml.safe_dump(boot, sort_keys=False))
    for name, config in runtime.items():
        private_write(output / (name + ".compose.yml"), yaml.safe_dump(compose_literal(config), sort_keys=False))
    print("应用配置来源：Nacos；启动文件：deploy/server/.runtime/*.compose.yml")
    print("未重建容器，未创建旧配置备份。以后启动不再需要 --env-file。")


if __name__ == "__main__":
    try:
        main()
    except urllib.error.HTTPError as error:
        raise SystemExit("Nacos 请求失败：HTTP " + str(error.code)) from None
    except ValueError as error:
        raise SystemExit(str(error)) from None
    except (OSError, KeyError, yaml.YAMLError, subprocess.TimeoutExpired):
        raise SystemExit("配置未完成，请检查 Docker Compose、Nacos 连接及 YAML 格式；未输出密钥") from None
