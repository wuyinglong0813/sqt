#!/usr/bin/env python3
"""Fill core WeChat/Fadada settings without editing or displaying private files."""
import argparse
import getpass
import os
from pathlib import Path
import re
import stat
import sys
import tempfile
import warnings
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[2]
KEYS = (
    "WECHAT_APP_ID", "WECHAT_APP_SECRET", "FADADA_ENABLED", "FADADA_APP_ID",
    "FADADA_APP_SECRET", "FADADA_SERVER_URL", "FADADA_CALLBACK_URL",
)
ASSIGNMENT = re.compile(r"^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$")


def read_settings(content):
    """Read only plain, single-line integration values; never source a shell file.

    Expressions and escaped/multiline values require fresh terminal input rather
    than guessing their resolved value. All unrelated settings remain untouched.
    """
    values = {}
    for line in content.splitlines():
        match = ASSIGNMENT.match(line)
        if not match or match.group(1) not in KEYS:
            continue
        key, raw = match.group(1), match.group(2).strip()
        if raw.startswith("'"):
            quoted = re.fullmatch(r"'([^'\r\n]*)'\s*(?:#.*)?", raw)
            value = quoted.group(1) if quoted else ""
        elif raw.startswith('"'):
            quoted = re.fullmatch(r'"([^"\\$`\r\n]*)"\s*(?:#.*)?', raw)
            value = quoted.group(1) if quoted else ""
        else:
            value = re.split(r"\s+#", raw, maxsplit=1)[0].strip()
            if any(char in value for char in "'\"\\$`"):
                value = ""
        values[key] = value
    return values


def prompt_value(key, secret=False):
    if not sys.stdin.isatty() or not sys.stderr.isatty():
        raise ValueError(key + " 缺少可复用的值，请在交互式终端运行脚本")
    if secret:
        with warnings.catch_warnings():
            warnings.simplefilter("error", getpass.GetPassWarning)
            return getpass.getpass(key + "（粘贴完整密钥，输入不显示）: ").strip()
    return input(key + ": ").strip()


def validate(values):
    for key in KEYS:
        value = values[key]
        # Single quotes keep dollar signs, spaces and shell characters literal
        # for both Compose dotenv and the existing environment-loading scripts.
        if not value or "'" in value or "\\" in value or any(ord(char) < 32 or ord(char) == 127 for char in value):
            raise ValueError(key + " 不能为空，也不能包含单引号、反斜杠或控制字符")
    if not re.fullmatch(r"wx[0-9a-fA-F]{16}", values["WECHAT_APP_ID"]):
        raise ValueError("WECHAT_APP_ID 格式不正确")
    if not re.fullmatch(r"[0-9a-fA-F]{32}", values["WECHAT_APP_SECRET"]):
        raise ValueError("WECHAT_APP_SECRET 应为完整的 32 位十六进制密钥")
    if values["FADADA_ENABLED"] != "true":
        raise ValueError("此脚本用于启用法大大配置")
    for key in ("FADADA_SERVER_URL", "FADADA_CALLBACK_URL"):
        parsed = urlsplit(values[key])
        if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
            raise ValueError(key + " 必须是完整的 HTTPS 地址")
        if any(char.isspace() for char in values[key]):
            raise ValueError(key + " 不能包含空白字符")


def choose_values(current, previous, args):
    wx_id = args.wechat_app_id or current.get("WECHAT_APP_ID") or previous.get("WECHAT_APP_ID")
    wx_id = wx_id or "wxd6d1e93a3868253e"
    # An incomplete, disabled core template must not override the old working
    # Fadada environment with the template's production URL.
    active = {}
    for candidate in (current, previous):
        if (candidate.get("FADADA_APP_ID") and candidate.get("FADADA_SERVER_URL")
                and (candidate.get("FADADA_ENABLED") == "true" or candidate.get("FADADA_APP_SECRET"))):
            active = candidate
            break
    fdd_id = args.fadada_app_id or active.get("FADADA_APP_ID") or prompt_value("FADADA_APP_ID")
    matching_url = active.get("FADADA_SERVER_URL") if active.get("FADADA_APP_ID") == fdd_id else None
    fdd_url = args.fadada_server_url or matching_url or prompt_value("FADADA_SERVER_URL")
    callback = (args.callback_url or current.get("FADADA_CALLBACK_URL")
                or previous.get("FADADA_CALLBACK_URL") or "https://sqt.org.cn/api/fadada/callback")

    def matching_secret(prefix, app_id, server_url=None):
        for settings in (current, previous):
            if settings.get(prefix + "_APP_ID") != app_id:
                continue
            if server_url and settings.get("FADADA_SERVER_URL", "").rstrip("/") != server_url.rstrip("/"):
                continue
            secret = settings.get(prefix + "_APP_SECRET", "")
            if prefix == "WECHAT" and not re.fullmatch(r"[0-9a-fA-F]{32}", secret):
                continue
            if secret:
                return secret
        return prompt_value(prefix + "_APP_SECRET", secret=True)

    values = {
        "WECHAT_APP_ID": wx_id,
        "WECHAT_APP_SECRET": matching_secret("WECHAT", wx_id),
        "FADADA_ENABLED": "true",
        "FADADA_APP_ID": fdd_id,
        "FADADA_APP_SECRET": matching_secret("FADADA", fdd_id, fdd_url),
        "FADADA_SERVER_URL": fdd_url,
        "FADADA_CALLBACK_URL": callback,
    }
    validate(values)
    return values


def render(content, values):
    validate(values)
    output, written = [], set()
    for line in content.splitlines(keepends=True):
        match = ASSIGNMENT.match(line.rstrip("\r\n"))
        key = match.group(1) if match else None
        if key not in values:
            output.append(line)
        elif key not in written:
            output.append("{}='{}'\n".format(key, values[key]))
            written.add(key)
    result = "".join(output)
    if result and not result.endswith("\n"):
        result += "\n"
    return result + "".join("{}='{}'\n".format(key, values[key]) for key in KEYS if key not in written)


def write_private(path, original, updated):
    if updated == original:
        return None
    if path.is_symlink() or not path.is_file() or path.read_bytes() != original:
        raise ValueError("目标配置已变化或不是普通文件，未写入")
    backup_fd, backup_name = tempfile.mkstemp(prefix=path.name + ".backup-", dir=str(path.parent))
    with os.fdopen(backup_fd, "wb") as out:
        os.fchmod(out.fileno(), 0o600)
        out.write(original)
        out.flush()
        os.fsync(out.fileno())
    temp_fd, temp_name = tempfile.mkstemp(prefix=path.name + ".update-", dir=str(path.parent))
    try:
        with os.fdopen(temp_fd, "wb") as out:
            os.fchmod(out.fileno(), 0o600)
            out.write(updated)
            out.flush()
            os.fsync(out.fileno())
        if path.read_bytes() != original:
            raise ValueError("配置在更新期间发生变化，未覆盖")
        os.replace(temp_name, str(path))
    finally:
        if os.path.exists(temp_name):
            os.unlink(temp_name)
    return backup_name


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, default=ROOT / "deploy/server/.env.core")
    parser.add_argument("--source", type=Path, help="旧环境文件，默认读取目标目录下的 .env")
    parser.add_argument("--wechat-app-id")
    parser.add_argument("--fadada-app-id")
    parser.add_argument("--fadada-server-url")
    parser.add_argument("--callback-url")
    args = parser.parse_args()
    path = args.env_file.absolute()
    if path.is_symlink() or not path.is_file():
        raise ValueError("找不到现有 .env.core 普通文件，请指定 --env-file")
    if path.stat().st_mode & (stat.S_IRWXG | stat.S_IRWXO):
        raise ValueError("请先执行 chmod 600，将目标环境文件设为私有")
    original = path.read_bytes()
    previous_path = args.source or path.parent / ".env"
    if previous_path.is_symlink():
        raise ValueError("旧环境文件不能是符号链接")
    if args.source and not previous_path.is_file():
        raise ValueError("指定的旧环境文件不存在")
    previous = read_settings(previous_path.read_text()) if previous_path.is_file() else {}
    current = read_settings(original.decode("utf-8"))
    values = choose_values(current, previous, args)
    updated = render(original.decode("utf-8"), values).encode("utf-8")
    backup = write_private(path, original, updated)
    print("已更新微信和法大大配置。" if backup else "配置已一致，无需修改。")
    if backup:
        print("原文件的私有备份：" + backup)
    print("其他配置保持原样；密钥未输出，容器尚未重建。")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, EOFError, KeyboardInterrupt, getpass.GetPassWarning) as error:
        # Do not print an arbitrary OS error that could embed private content.
        message = str(error) if isinstance(error, ValueError) else "操作未完成，请检查文件路径、权限或终端输入"
        raise SystemExit(message) from None
