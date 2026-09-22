#!/usr/bin/env bash
# Sourced by the server installers. No system changes on source.
set -euo pipefail
set +x

die() { echo "错误：$*" >&2; exit 1; }
detect_system() {
  [[ $(uname -s) == Linux ]] || die '仅在 Linux 服务器运行；支持 Ubuntu 22.04/24.04、Debian 12/13。'
  [[ -r /etc/os-release ]] || die '无法读取 /etc/os-release。'
  # This file is owned by the operating system, never by the release bundle.
  . /etc/os-release
  case "$ID:$VERSION_ID" in
    ubuntu:22.04|ubuntu:24.04|debian:12|debian:13) ;;
    *) die "尚未适配 $ID $VERSION_ID。请提供 cat /etc/os-release 的结果；脚本没有修改系统。" ;;
  esac
  case $(dpkg --print-architecture) in
    amd64) TP_ARCH=amd64; TP_NODE_ARCH=x64 ;;
    arm64) TP_ARCH=arm64; TP_NODE_ARCH=arm64 ;;
    *) die '仅支持 amd64/arm64。' ;;
  esac
  command -v systemctl >/dev/null && [[ -d /run/systemd/system ]] || die '需要使用 systemd 的完整服务器系统。'
  echo "系统：$PRETTY_NAME / $TP_ARCH；CPU：$(getconf _NPROCESSORS_ONLN)；内存 MiB：$(awk '/MemTotal/ {print int($2/1024)}' /proc/meminfo)"
}
require_root() { [[ $EUID == 0 ]] || die '安装需要 root：请使用 sudo bash 执行此脚本。'; }
local_docker() {
  [[ -z ${DOCKER_HOST:-} && -z ${DOCKER_CONTEXT:-} ]] || die '请取消 DOCKER_HOST/DOCKER_CONTEXT，安装器只管理本机 /var/run/docker.sock。'
  export DOCKER_HOST=unix:///var/run/docker.sock
}
download() { curl --fail --show-error --silent --location --proto '=https' --tlsv1.2 --retry 3 --connect-timeout 20 --max-time 600 "$1" -o "$2"; }
installed() { [[ $(dpkg-query -W -f='${Status}' "$1" 2>/dev/null || true) == 'install ok installed' ]]; }

# A previous run may have stopped after writing a repository. Reuse only an identical file.
install_config() {
  local source_file=$1 target_file=$2
  if [[ -e $target_file ]]; then
    cmp -s "$source_file" "$target_file" || die "已有不同配置 $target_file，请人工检查；没有覆盖。"
  else
    install -m 0644 "$source_file" "$target_file"
  fi
}
