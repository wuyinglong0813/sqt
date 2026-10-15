#!/usr/bin/env python3
"""Read a running core deployment's company identity evidence without changing business state."""
import argparse
import hashlib
import hmac
import importlib.util
import json
from pathlib import Path
import re
import sys
import time
import urllib.parse
import urllib.request
import uuid

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parent))
import mq_resources as config


class DiagnosticError(Exception):
    """Only fixed, credential-free messages may reach the terminal."""


def emit(section, value):
    print(section + " " + json.dumps(value, ensure_ascii=False, sort_keys=True), flush=True)


def running_configuration(info):
    env = dict(item.split("=", 1) for item in info["Config"].get("Env", []))
    if info["HostConfig"].get("NetworkMode") != "host":
        raise DiagnosticError("仅支持 core host 网络部署")
    if env.get("SPRING_APPLICATION_NAME", "tradepass-identity") != "tradepass-identity":
        raise DiagnosticError("identity 容器的 application name 不符合预期")
    profiles = set(env.get("SPRING_PROFILES_ACTIVE", "").split(","))
    if "core" not in profiles:
        raise DiagnosticError("未确认 identity 容器启用 core profile")
    for name in ("SPRING_APPLICATION_JSON", "SPRING_CONFIG_IMPORT", "SPRING_CONFIG_LOCATION", "SPRING_PROFILES_INCLUDE"):
        if env.get(name):
            raise DiagnosticError("存在不支持的配置覆盖：" + name)
    arguments = json.dumps([info["Config"].get("Cmd"), info["Config"].get("Entrypoint"),
                           *[env.get(key, "") for key in ("JAVA_OPTS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")]])
    if any(key in arguments.lower() for key in ("tradepass.fadada", "spring.cloud.nacos", "spring.config.",
                                               "spring.datasource", "spring.profiles.", "spring.application.name", "fadada_", "nacos_")):
        raise DiagnosticError("启动参数存在未解析的配置覆盖")
    bootstrap = None
    location = env.get("SPRING_CONFIG_ADDITIONAL_LOCATION", "")
    mounts = [m for m in info.get("Mounts", []) if m["Destination"] == "/app/nacos-bootstrap.yml"]
    if location or mounts:
        if location != "file:/app/nacos-bootstrap.yml" or len(mounts) != 1 or mounts[0].get("Type") != "bind":
            raise DiagnosticError("无法确认 Nacos bootstrap 挂载")
        bootstrap = Path(mounts[0]["Source"])
    documents = [{}, {}]
    if bootstrap or "nacos" in profiles:
        options = config.nacos_options(bootstrap, env)
        spec = importlib.util.spec_from_file_location("company_auth_nacos", config.CONFIGURATOR)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        values = {"NACOS_" + key: options.get(prop, "") for key, prop in (
            ("SERVER_ADDR", "server-addr"), ("GROUP", "group"), ("NAMESPACE", "namespace"),
            ("USERNAME", "username"), ("PASSWORD", "password"))}
        client = module.Nacos(values)
        documents = [yaml.safe_load(client.read(role) or "null") for role in ("common", "identity")]
        if not all(isinstance(doc, dict) for doc in documents):
            raise DiagnosticError("Nacos common/identity 配置缺失")
    return env, documents


def property_value(env, documents, name, alias=None, default=None):
    missing = object()
    def resolve(key, depth=0):
        if depth > 10:
            raise DiagnosticError("配置占位符存在循环")
        value = config.environment_value(env, key, missing)
        if value is missing:
            value = config.get_path(documents[1], key, config.get_path(documents[0], key, missing))
        if value is missing:
            return missing
        if isinstance(value, str):
            def replace(match):
                nested, fallback = match.groups()
                replacement = env.get(nested, missing)
                if replacement is missing:
                    replacement = resolve(nested, depth + 1)
                if replacement is missing:
                    if fallback is None:
                        raise DiagnosticError("配置含未解析占位符")
                    replacement = fallback
                return str(replacement)
            value = re.sub(r"\$\{([^:{}]+)(?::([^{}]*))?}", replace, value)
            if "${" in value:
                raise DiagnosticError("配置含不支持的占位符")
        return value
    value = resolve(name)
    fallback = env.get(alias, default) if alias else default
    if value is missing:
        return fallback
    # An old environment alias can compete with imported Nacos configuration.
    if alias in env and config.environment_value(env, name, missing) is missing:
        if str(value).lower() != str(env[alias]).lower():
            raise DiagnosticError("Nacos 与容器环境配置冲突：" + name)
    return value


def signature(headers, form, secret):
    # FddCryptUtil.sign/sortParameters from the pinned official 5.8.7.0428.3 SDK.
    values = {**headers, **form}
    text = "&".join(k + "=" + values[k] for k in sorted(values) if values[k].strip())
    digest = hashlib.sha256(text.encode()).hexdigest()
    key = hmac.new(secret.encode(), headers["X-FASC-Timestamp"].encode(), hashlib.sha256).digest()
    return hmac.new(key, digest.encode(), hashlib.sha256).hexdigest()


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise DiagnosticError("法大大接口发生重定向，已停止")


class Provider:
    def __init__(self, server, app_id, secret):
        self.server = str(server).rstrip("/")
        if self.server not in ("https://api.fadada.com/api/v5", "https://uat-api.fadada.com/api/v5"):
            raise DiagnosticError("只允许当前支持的法大大正式/沙箱 API 地址")
        self.app_id, self.secret = app_id, secret
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def call(self, path, body=None, token=None, legacy_identity=False):
        if path not in ("/service/get-access-token", "/corp/get-identity-info", "/user/get", "/user/get-identity-info"):
            raise DiagnosticError("诊断禁止调用业务写入接口")
        if legacy_identity and path != "/corp/get-identity-info":
            raise DiagnosticError("旧版兼容查询仅限企业实名信息")
        headers = {"X-FASC-App-Id": self.app_id,
                   "X-FASC-Sign-Type": "HMAC-SHA256", "X-FASC-Timestamp": str(int(time.time() * 1000)),
                   "X-FASC-Nonce": uuid.uuid4().hex}
        # The official 5.0 request omits this header, including it in neither request nor signature.
        if not legacy_identity:
            headers["X-FASC-Api-SubVersion"] = "5.1"
        headers["X-FASC-AccessToken" if token else "X-FASC-Grant-Type"] = token or "client_credential"
        form = {} if body is None else {"bizContent": json.dumps(body, ensure_ascii=False, separators=(",", ":"))}
        headers["X-FASC-Sign"] = signature(headers, form, self.secret)
        request = urllib.request.Request(self.server + path, data=urllib.parse.urlencode(form).encode(), headers=headers)
        with self.opener.open(request, timeout=20) as response:
            raw = response.read(1024 * 1024 + 1)
            if len(raw) > 1024 * 1024:
                raise DiagnosticError("法大大响应超出诊断大小限制")
            result = json.loads(raw)
            request_id = response.headers.get("X-FASC-Request-Id", "")
        # Never print response messages/bodies: they can contain personal data or credentials.
        code = str(result.get("code", ""))
        emit("PROVIDER_REQUEST", {"path": path, "version": "5.0-default" if legacy_identity else "5.1",
                                  "code": code if re.fullmatch(r"[0-9]{1,10}", code) else "unknown",
                                  "requestId": request_id if re.fullmatch(r"[A-Za-z0-9-]{1,100}", request_id) else "unavailable"})
        if code != "100000" or not isinstance(result.get("data"), dict):
            raise DiagnosticError("法大大查询未成功，请按上方 code/requestId 核查")
        return result["data"]


def field_state(data, key):
    if key not in data:
        return "absent"
    value = data[key]
    if value is None:
        return "null"
    if isinstance(value, str):
        return "present" if value.strip() else "empty"
    return "unexpected_type"


def identity_report(data, local):
    operator = data.get("operatorId")
    info = data.get("corpIdentInfo") or {}
    return {
        "fields": {key: field_state(data, key) for key in (
            "openCorpId", "corpIdentStatus", "corpIdentMethod", "operatorType", "operatorId", "operatorIdentMethod")},
        "operatorType": data.get("operatorType") if data.get("operatorType") in ("legal_rep", "deputy_auth") else "unavailable_or_unknown",
        "corpIdentMethod": data.get("corpIdentMethod") if data.get("corpIdentMethod") in (
            "legal_rep", "deputy_auth", "payment", "offline") else "unavailable_or_unknown",
        "identified": data.get("corpIdentStatus") == "identified",
        "companyBindingMatches": bool(local.get("openCorpId")) and data.get("openCorpId") == local["openCorpId"],
        "companyNameMatches": bool(local.get("name")) and info.get("corpName") == local["name"],
        "creditCodeMatches": bool(local.get("creditCode")) and info.get("corpIdentNo") == local["creditCode"],
        "operatorMatchesOpenUserId": bool(operator and local.get("openUserId")) and operator == local["openUserId"],
        "operatorMatchesClientUserId": bool(operator and local.get("clientUserId")) and operator == local["clientUserId"],
    }


def require_current_personal_account(data, local):
    # Resolve by clientUserId first: a stale local openUserId must not select another person.
    if not local.get("clientUserId") or not local.get("openUserId"):
        raise DiagnosticError("个人实名账号关联缺失，未查询个人实名资料")
    if data.get("clientUserId") != local["clientUserId"] or data.get("openUserId") != local["openUserId"]:
        raise DiagnosticError("个人实名账号关联不一致，未查询个人实名资料")
    scopes = data.get("authScope")
    if isinstance(scopes, str):
        scopes = scopes.split(",")
    if (data.get("availableStatus") != "enable" or data.get("bindingStatus") != "authorized"
            or not isinstance(scopes, list) or "ident_info" not in scopes):
        raise DiagnosticError("个人电子签账号未启用或未授权身份信息，未查询个人实名资料")


def certificate_number_state(info):
    value = info.get("identNo")
    if value is None or (isinstance(value, str) and not value.strip()):
        return "missing"
    if not isinstance(value, str):
        return "invalid_or_unknown"
    # Do not echo the value, even on malformed or unrecognised provider responses.
    if re.search(r"[*＊#＃●•×…]|[xX]{2,}", value):
        return "masked"
    if info.get("identType") == "id_card" and re.fullmatch(r"(?:[0-9]{15}|[0-9]{17}[0-9Xx])", value):
        # This is a format classification, not an assertion that the number passed a new check.
        return "full_format"
    return "invalid_or_unknown"


def personal_identity_report(data, local, company_data):
    if not local.get("openUserId") or data.get("openUserId") != local["openUserId"]:
        raise DiagnosticError("个人实名响应主体不一致，未生成个人身份取证结果")
    if data.get("identStatus") != "identified":
        raise DiagnosticError("个人实名尚未通过或已失效，未生成个人身份取证结果")
    if not local.get("openCorpId") or company_data.get("openCorpId") != local["openCorpId"]:
        raise DiagnosticError("企业实名响应主体不一致，未比对个人与企业身份")
    if company_data.get("corpIdentStatus") != "identified":
        raise DiagnosticError("企业实名尚未通过或已失效，未比对个人与企业身份")
    info = data.get("userIdentInfo")
    company_info = company_data.get("corpIdentInfo")
    if not isinstance(info, dict) or not isinstance(company_info, dict):
        raise DiagnosticError("实名身份信息结构缺失，未生成个人身份取证结果")
    # A matching name is diagnostic context only; it must never confer legal-representative rights.
    user_name, legal_name = info.get("userName"), company_info.get("legalRepName")
    names_present = (isinstance(user_name, str) and bool(user_name.strip())
                     and isinstance(legal_name, str) and bool(legal_name.strip()))
    number_state = certificate_number_state(info)
    return {
        "identified": True,
        "personalBindingMatches": True,
        "companyBindingMatches": True,
        "nameFieldsPresent": names_present,
        "verifiedNameEqualsLegalRepName": names_present and user_name.strip() == legal_name.strip(),
        "identityCardType": info.get("identType") == "id_card",
        "certificateNumberState": number_state,
        "certificateNumberHasFullIdentityCardFormat": number_state == "full_format",
        "certificateNumberMasked": number_state == "masked",
        "certificateNumberMissing": number_state == "missing",
        "fddIdPresent": field_state(data, "fddId") == "present",
        "nameMatchProvesLegalRepresentative": False,
    }


def collect_personal_evidence(provider, token, local, company_data):
    if not local.get("clientUserId") or not local.get("openUserId"):
        raise DiagnosticError("个人实名账号关联缺失，未查询个人实名资料")
    account = provider.call("/user/get", {"clientUserId": local["clientUserId"]}, token)
    require_current_personal_account(account, local)
    data = provider.call("/user/get-identity-info", {"openUserId": local["openUserId"]}, token)
    return personal_identity_report(data, local, company_data)


def read_company(database, company_id):
    if not re.fullmatch(r"[A-Za-z0-9_]+", database) or not re.fullmatch(r"[1-9][0-9]{0,18}", company_id):
        raise DiagnosticError("数据库名或企业 ID 无效")
    sql = """SELECT JSON_OBJECT('openCorpId', ci.open_corp_id, 'openUserId', ui.open_user_id,
        'clientUserId', ui.client_user_id, 'name', c.name, 'creditCode', c.credit_code,
        'companyStatus', c.certification_status, 'identityStatus', ci.local_status,
        'personalStatus', ui.local_status)
        FROM `{db}`.company c JOIN `{db}`.fadada_corp_identity ci ON ci.company_id = c.id
        LEFT JOIN `{db}`.fadada_user_identity ui ON ui.user_id = ci.applicant_user_id
        WHERE c.id = {id};""".format(db=database, id=company_id)
    output = config.run("docker", "exec", "-i", "tradepass-infra-static-mysql-1", "sh", "-c",
                        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4 --batch --raw --skip-column-names',
                        input=sql)
    if not output:
        raise DiagnosticError("未找到该企业的认证记录")
    return json.loads(output)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--company-id", required=True)
    parser.add_argument("--identity-database", help="identity 库名；无法从运行配置识别时使用")
    parser.add_argument("--include-personal-evidence", action="store_true",
                        help="额外只读核对申请人的当前实名关联；仅输出证件完整/脱敏/缺失及姓名比对状态，不输出身份值")
    parser.add_argument("--legacy-identity", action="store_true",
                        help="企业实名查询按官方5.0请求省略子版本头，仅读取字段兼容性，不修改应用版本或企业身份")
    args = parser.parse_args(argv)
    if not re.fullmatch(r"[1-9][0-9]{0,18}", args.company_id):
        raise DiagnosticError("企业 ID 必须为正整数")
    info = json.loads(config.run("docker", "inspect", "tradepass-core-identity-1"))[0]
    for role, container in (("identity", info), ("business", json.loads(config.run("docker", "inspect", "tradepass-core-business-1"))[0])):
        image = json.loads(config.run("docker", "image", "inspect", container["Image"]))[0]
        labels = image["Config"].get("Labels") or {}
        revision = labels.get("org.opencontainers.image.revision", "")
        emit("RUNNING_IMAGE", {"role": role, "revision": revision if re.fullmatch(r"[a-f0-9]{40}", revision) else "unavailable",
                               "imageId": container["Image"], "startedAt": container["State"]["StartedAt"]})
    env, documents = running_configuration(info)
    get = lambda key, alias=None, default=None: property_value(env, documents, key, alias, default)
    if str(get("tradepass.fadada.enabled", "FADADA_ENABLED", False)).lower() != "true":
        raise DiagnosticError("identity 未启用法大大")
    app_id = get("tradepass.fadada.app-id", "FADADA_APP_ID")
    secret = get("tradepass.fadada.app-secret", "FADADA_APP_SECRET")
    if not isinstance(app_id, str) or not app_id.strip() or not isinstance(secret, str) or not secret.strip():
        raise DiagnosticError("法大大应用配置不完整")
    database = args.identity_database
    if not database:
        database_url = get("spring.datasource.url", "IDENTITY_DATABASE_URL")
        match = re.fullmatch(r"jdbc:mysql://(?:127\.0\.0\.1|localhost):[0-9]+/([A-Za-z0-9_]+)(?:\?.*)?", str(database_url))
        if not match:
            raise DiagnosticError("无法确认 identity 使用的本机 MySQL 数据库，请用 --identity-database 指定")
        database = match.group(1)
    local = read_company(database, args.company_id)
    emit("LOCAL_STATUS", {key: local.get(key) for key in ("companyStatus", "identityStatus", "personalStatus")})
    if not local.get("openCorpId"):
        raise DiagnosticError("企业尚无 openCorpId，无法查询实名详情")
    provider = Provider(get("tradepass.fadada.server-url", "FADADA_SERVER_URL", "https://api.fadada.com/api/v5"),
                        app_id.strip(), secret.strip())
    token = provider.call("/service/get-access-token").get("accessToken")
    if not isinstance(token, str) or not token.strip():
        raise DiagnosticError("法大大未返回查询令牌")
    identity_args = {"legacy_identity": True} if args.legacy_identity else {}
    data = provider.call("/corp/get-identity-info", {"openCorpId": local["openCorpId"]}, token, **identity_args)
    emit("IDENTITY_EVIDENCE", identity_report(data, local))
    if args.include_personal_evidence:
        emit("PERSONAL_IDENTITY_EVIDENCE", collect_personal_evidence(provider, token, local, data))
    print("DIAGNOSTIC_COMPLETE（仅完成取证，不代表企业已开通）")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except DiagnosticError as error:
        print("错误：" + str(error), file=sys.stderr)
        sys.exit(1)
    except Exception as error:
        # YAML, Docker, network and SDK payload errors can contain secrets. Print type only.
        print("诊断未完成：" + type(error).__name__ + "（未输出私有配置或响应正文）", file=sys.stderr)
        sys.exit(1)
