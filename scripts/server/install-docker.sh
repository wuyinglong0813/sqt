#!/usr/bin/env bash
set -euo pipefail
set +x
TP_SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
. "$TP_SCRIPT_DIR/common.sh"
case ${1:---plan} in --plan|--install) TP_MODE=${1:---plan} ;; *) die '用法：install-docker.sh [--plan|--install]' ;; esac
detect_system
if [[ $TP_MODE == --plan ]]; then
  echo '计划：检查已有容器运行时；缺少 Docker 时配置 Docker 官方 apt 仓库，安装 Engine、Buildx、Compose、Python 3。'
  echo '不卸载已有运行时，不执行系统整体升级，不改 SSH、防火墙或 daemon.json。'
  command -v docker || true
  exit 0
fi
require_root
local_docker
if command -v docker >/dev/null; then
  docker info >/dev/null || die '发现 Docker 但 daemon 不可用。请先检查 systemctl status docker；没有重装或重启。'
  docker compose version >/dev/null || die '已有 Docker 缺少 Compose 插件；请先按该 Docker 的来源补装插件。'
  docker buildx version >/dev/null || die '已有 Docker 缺少 Buildx 插件；请先按该 Docker 的来源补装插件。'
  docker compose up --help | grep -q -- --wait-timeout || die '已有 Compose 版本过旧，需要支持 --wait-timeout。'
  echo '已有可用 Docker/Compose/Buildx，保留当前版本。'
else
  [[ ! -d /var/lib/rancher/k3s && ! -d /etc/kubernetes ]] || die '发现 K3s/Kubernetes，请先确认容器运行时规划。'
  for TP_PACKAGE in docker.io docker-compose docker-compose-v2 docker-doc podman-docker containerd runc; do
    installed "$TP_PACKAGE" && die "发现已有 $TP_PACKAGE，停止自动安装以保留现有运行时。"
  done
  if [[ -d /var/lib/docker ]] && [[ -n $(ls -A /var/lib/docker) ]]; then
    die '发现历史 /var/lib/docker 数据但没有 Docker CLI；请人工核对，安装器不会接管旧数据。'
  fi
  export DEBIAN_FRONTEND=noninteractive
  apt-get update
  apt-get install -y --no-install-recommends ca-certificates curl
  TP_TMP=$(mktemp -d)
  trap 'rm -rf -- "$TP_TMP"' EXIT
  install -m 0755 -d /etc/apt/keyrings
  download "https://download.docker.com/linux/$ID/gpg" "$TP_TMP/docker.asc"
  install_config "$TP_TMP/docker.asc" /etc/apt/keyrings/docker.asc
  # Avoid a duplicate repository with conflicting Signed-By settings.
  if grep -Rqs 'download.docker.com' /etc/apt/sources.list /etc/apt/sources.list.d 2>/dev/null; then
    [[ -f /etc/apt/sources.list.d/tradepass-docker.sources ]] || die '发现已有 Docker apt 源；请先核对，不自动叠加。'
  fi
  cat > "$TP_TMP/docker.sources" <<EOF
Types: deb
URIs: https://download.docker.com/linux/$ID
Suites: $VERSION_CODENAME
Components: stable
Architectures: $TP_ARCH
Signed-By: /etc/apt/keyrings/docker.asc
EOF
  install_config "$TP_TMP/docker.sources" /etc/apt/sources.list.d/tradepass-docker.sources
  apt-get update
  apt-get install -y --no-install-recommends docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
  systemctl enable --now docker
fi
export DEBIAN_FRONTEND=noninteractive
apt-get install -y --no-install-recommends python3 python3-yaml ca-certificates curl
docker info --format 'Docker Server: {{.ServerVersion}}'
docker compose version
docker buildx version
echo 'Docker 安装完成。下一步运行 bootstrap.sh --install，并选择需要的可选组件。'
