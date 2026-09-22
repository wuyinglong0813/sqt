#!/usr/bin/env python3
"""Align tradepass module packages to yudao-style layers (api/enums + server layers)."""
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODULES = ("identity", "contract", "trade", "settlement", "file")
PORTS = {
    "identity": 1111,
    "contract": 1112,
    "trade": 1113,
    "settlement": 1114,
    "file": 1115,
}
SERVER_LAYERS = ("api", "controller", "convert", "dal", "framework", "job", "service", "util")
API_LAYERS = ("api", "enums")


def write_if_missing(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists():
        path.write_text(content, encoding="utf-8")


def package_info(pkg: str, title: str) -> str:
    return f'/**\n * {title}\n */\npackage {pkg};\n'


def role_title(role: str) -> str:
    return {
        "identity": "身份认证",
        "contract": "合同签署",
        "trade": "交易履约",
        "settlement": "结算对账",
        "file": "文件存储",
    }[role]


def move_configuration(role: str) -> None:
    server = ROOT / f"tradepass-module-{role}/tradepass-module-{role}-server"
    old = server / f"src/main/java/com/tradepass/module/{role}/{role.capitalize()}Configuration.java"
    # File uses FileRuntimeConfiguration already under framework
    if role == "file":
        return
    class_name = f"{role.capitalize()}Configuration"
    old = server / f"src/main/java/com/tradepass/module/{role}/{class_name}.java"
    if not old.exists():
        # Settlement etc.
        mapping = {
            "identity": "IdentityConfiguration",
            "contract": "ContractConfiguration",
            "trade": "TradeConfiguration",
            "settlement": "SettlementConfiguration",
        }
        class_name = mapping[role]
        old = server / f"src/main/java/com/tradepass/module/{role}/{class_name}.java"
    if not old.exists():
        print(f"skip config move: {role}")
        return
    text = old.read_text(encoding="utf-8")
    text = text.replace(f"package com.tradepass.module.{role};", f"package com.tradepass.module.{role}.framework.config;")
    dest = server / f"src/main/java/com/tradepass/module/{role}/framework/config/{class_name}.java"
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text(text, encoding="utf-8")
    old.unlink()
    print(f"moved {class_name} -> framework.config")


def rename_application(role: str) -> None:
    server = ROOT / f"tradepass-module-{role}/tradepass-module-{role}-server"
    mapping = {
        "identity": ("IdentityApplication", "IdentityServerApplication", "IdentityConfiguration"),
        "contract": ("ContractApplication", "ContractServerApplication", "ContractConfiguration"),
        "trade": ("TradeApplication", "TradeServerApplication", "TradeConfiguration"),
        "settlement": ("SettlementApplication", "SettlementServerApplication", "SettlementConfiguration"),
        "file": ("FileApplication", "FileServerApplication", None),
    }
    old_name, new_name, config = mapping[role]
    old = server / f"src/main/java/com/tradepass/module/{role}/{old_name}.java"
    if not old.exists():
        print(f"skip app rename: {role}")
        return
    if role == "file":
        body = f'''package com.tradepass.module.{role};

import com.tradepass.framework.runtime.core.ServiceLauncher;
import com.tradepass.module.file.framework.config.FileRuntimeConfiguration;

/**
 * {role_title(role)}服务启动类。
 */
public final class {new_name} {{
    public static void main(String[] args) {{
        ServiceLauncher.run("{role}", {PORTS[role]}, FileRuntimeConfiguration.class, args);
    }}
}}
'''
    else:
        body = f'''package com.tradepass.module.{role};

import com.tradepass.framework.runtime.core.ServiceLauncher;
import com.tradepass.module.{role}.framework.config.{config};

/**
 * {role_title(role)}服务启动类。
 */
public final class {new_name} {{
    public static void main(String[] args) {{
        ServiceLauncher.run("{role}", {PORTS[role]}, {config}.class, args);
    }}
}}
'''
    new = server / f"src/main/java/com/tradepass/module/{role}/{new_name}.java"
    new.write_text(body, encoding="utf-8")
    old.unlink()
    pom = server / "pom.xml"
    pom.write_text(
        pom.read_text(encoding="utf-8").replace(
            f"com.tradepass.module.{role}.{old_name}",
            f"com.tradepass.module.{role}.{new_name}",
        ),
        encoding="utf-8",
    )
    print(f"renamed {old_name} -> {new_name}")


def ensure_package_infos(role: str) -> None:
    api_root = ROOT / f"tradepass-module-{role}/tradepass-module-{role}-api/src/main/java/com/tradepass/module/{role}"
    server_root = ROOT / f"tradepass-module-{role}/tradepass-module-{role}-server/src/main/java/com/tradepass/module/{role}"
    write_if_missing(
        api_root / "package-info.java",
        package_info(f"com.tradepass.module.{role}", f"{role_title(role)}模块 API：跨服务契约（接口 / DTO / 枚举）"),
    )
    for layer in API_LAYERS:
        write_if_missing(
            api_root / layer / "package-info.java",
            package_info(
                f"com.tradepass.module.{role}.{layer}",
                "跨服务 API 接口与传输对象" if layer == "api" else "模块对外枚举（供其它服务依赖）",
            ),
        )
    write_if_missing(
        server_root / "package-info.java",
        package_info(f"com.tradepass.module.{role}", f"{role_title(role)}模块服务端实现（对齐 yudao server 分层）"),
    )
    titles = {
        "api": "API 门面实现（*ApiImpl / *OperationsImpl），委托 service",
        "controller": "HTTP Controller（app / admin / internal）与 VO",
        "convert": "MapStruct 转换（DO ↔ VO / DTO）",
        "dal": "数据访问（dataobject / mysql Mapper）",
        "framework": "模块内框架扩展与配置",
        "job": "定时任务 / XXL-JOB / 本地调度",
        "service": "业务 Service 接口与 Impl",
        "util": "模块内工具类",
    }
    for layer in SERVER_LAYERS:
        write_if_missing(
            server_root / layer / "package-info.java",
            package_info(f"com.tradepass.module.{role}.{layer}", titles[layer]),
        )
    # nested dal docs
    write_if_missing(
        server_root / "dal/dataobject/package-info.java",
        package_info(f"com.tradepass.module.{role}.dal.dataobject", "数据库实体 DO"),
    )
    write_if_missing(
        server_root / "dal/mysql/package-info.java",
        package_info(f"com.tradepass.module.{role}.dal.mysql", "MyBatis Mapper"),
    )


def main() -> None:
    for role in MODULES:
        move_configuration(role)
        rename_application(role)
        ensure_package_infos(role)
    print("done")


if __name__ == "__main__":
    main()
