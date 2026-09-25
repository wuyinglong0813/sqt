#!/usr/bin/env python3
"""Manage the existing three-process TradePass deployment (Python standard library only)."""
import argparse
import json
import subprocess
import sys
import time


# Ordered dependencies first; stopping reverses this order.
SERVICES = (
    "mysql", "redis", "nacos", "rocketmq-namesrv", "rocketmq-broker",
    "identity", "business", "gateway", "nginx",
)
GROUPS = {"all": SERVICES, "apps": SERVICES[5:8], "infra": SERVICES[:5]}
DEPENDENCIES = {
    "rocketmq-broker": ("rocketmq-namesrv",),
    "identity": ("mysql", "redis", "nacos"),
    "business": ("identity", "rocketmq-broker"),
    "gateway": ("identity", "business"),
    "nginx": ("gateway",),
}


class OperationError(Exception):
    pass


def container(service):
    project = "edge" if service == "nginx" else "core" if service in GROUPS["apps"] else "infra-static"
    return "tradepass-{}-{}-1".format(project, service)


def select(targets, dependencies=False):
    selected = set()

    def add(service):
        if service in selected:
            return
        selected.add(service)
        if dependencies:
            for dependency in DEPENDENCIES.get(service, ()):
                add(dependency)

    for target in targets:
        for service in GROUPS.get(target, (target,)):
            add(service)
    return [service for service in SERVICES if service in selected]


def docker(*arguments, timeout=30):
    try:
        return subprocess.run(["docker", *arguments], capture_output=True,
                              text=True, timeout=timeout)
    except FileNotFoundError:
        raise OperationError("未找到 docker 命令") from None
    except subprocess.TimeoutExpired:
        raise OperationError("Docker 命令超时：" + arguments[0]) from None


def state(service):
    result = docker("inspect", "--type", "container", "--format", "{{json .State}}", container(service))
    if result.returncode:
        raise OperationError(service + "：无法读取容器；请检查容器是否存在以及 Docker 权限")
    try:
        return json.loads(result.stdout)
    except (ValueError, TypeError):
        raise OperationError(service + "：无法解析 Docker 状态") from None


def ready(service, current):
    return current.get("Status") == "running" and (
        current.get("Health", {}).get("Status") == "healthy"
        or (service == "nginx" and "Health" not in current)
    )


def describe(current):
    return "{} / {} / exit={} / oom={}".format(
        current.get("Status", "unknown"), current.get("Health", {}).get("Status", "无健康检查"),
        current.get("ExitCode", "?"), current.get("OOMKilled", False),
    )


def wait_ready(service, timeout):
    deadline = time.monotonic() + timeout
    while True:
        current = state(service)
        if ready(service, current):
            print("[就绪] " + service, flush=True)
            return
        if current.get("Status") in ("exited", "dead", "paused", "removing"):
            raise OperationError(service + " 启动失败：" + describe(current))
        if time.monotonic() >= deadline:
            raise OperationError(service + " 等待健康超时：" + describe(current))
        time.sleep(min(2, max(0, deadline - time.monotonic())))


def start(services, timeout):
    for service in services:
        current = state(service)
        if current.get("Status") not in ("running", "restarting"):
            print("[启动] " + service, flush=True)
            result = docker("start", container(service), timeout=60)
            if result.returncode:
                raise OperationError(service + " 启动失败；请检查 Docker 服务和容器日志")
        wait_ready(service, timeout)


def stop(services, timeout):
    for service in reversed(services):
        print("[停止] " + service, flush=True)
        result = docker("stop", "--time", str(timeout), container(service), timeout=timeout + 30)
        if result.returncode:
            raise OperationError(service + " 停止失败；已中止后续操作")
        if state(service).get("Status") not in ("exited", "created"):
            raise OperationError(service + " 尚未停止；已中止后续操作")


def status(services, check):
    failed = False
    print("服务                 状态 / 健康 / 退出码 / OOM")
    for service in services:
        try:
            current = state(service)
            print("{:<20} {}".format(service, describe(current)))
            failed |= check and not ready(service, current)
        except OperationError as error:
            print(str(error))
            failed = True
    return int(failed)


def positive(value):
    number = int(value)
    if number < 1:
        raise argparse.ArgumentTypeError("必须大于 0")
    return number


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="TradePass 现有容器管理；默认操作 all，不创建容器、不读取密码。",
        epilog="start 自动补齐依赖；stop 只停所选服务；restart 只重启所选服务，启动时补齐依赖。"
               "status 展示状态；check 对未就绪返回非零。nginx 无健康检查时仅验证运行状态。",
    )
    parser.add_argument("action", choices=("start", "stop", "restart", "status", "check"))
    parser.add_argument("targets", nargs="*", metavar="服务或分组",
                        help="all/apps/infra 或 " + "/".join(SERVICES))
    parser.add_argument("--timeout", type=positive, default=360, help="每个服务等待健康的秒数，默认 360")
    parser.add_argument("--stop-timeout", type=positive, default=90, help="优雅停止秒数，默认 90")
    parser.add_argument("--dry-run", action="store_true", help="仅显示执行顺序，不连接 Docker")
    args = parser.parse_args(argv)
    targets = args.targets or ["all"]
    unknown = set(targets) - set(SERVICES) - set(GROUPS)
    if unknown:
        parser.error("未知服务或分组：" + ", ".join(sorted(unknown)))
    selected = select(targets)
    starting = select(targets, dependencies=True)
    if args.action in ("stop", "restart"):
        print("停止顺序：" + " → ".join(reversed(selected)), flush=True)
        if targets != ["all"]:
            print("仅停止所选服务；依赖它们的其他服务可能暂时不可用。", flush=True)
    if args.action in ("start", "restart"):
        print("启动/检查依赖顺序：" + " → ".join(starting), flush=True)
    if args.dry_run:
        if args.action in ("status", "check"):
            print("检查范围：" + ", ".join(selected))
        return 0
    if docker("info", "--format", "{{.ServerVersion}}").returncode:
        raise OperationError("无法连接 Docker；请检查 daemon、context 和当前用户权限")
    if args.action in ("status", "check"):
        return status(selected, check=args.action == "check")
    # Fail before stopping anything if any needed container is missing or paused.
    required = starting if args.action in ("start", "restart") else selected
    for service in required:
        current = state(service)
        if current.get("Status") not in ("running", "restarting", "exited", "created"):
            raise OperationError(service + " 状态不支持启停：" + describe(current))
    if args.action in ("stop", "restart"):
        stop(selected, args.stop_timeout)
    if args.action in ("start", "restart"):
        start(starting, args.timeout)
    return status(selected, check=args.action != "stop")


if __name__ == "__main__":
    try:
        sys.exit(main())
    except OperationError as error:
        print("错误：" + str(error), file=sys.stderr)
        print("已停止后续操作；已完成的启停保留，不自动回滚。", file=sys.stderr)
        sys.exit(1)
    except KeyboardInterrupt:
        print("操作已中断；请执行 status 确认当前状态。", file=sys.stderr)
        sys.exit(130)
