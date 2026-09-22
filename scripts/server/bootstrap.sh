#!/usr/bin/env bash
# Run on the target server, never on the developer's workstation.
set -euo pipefail
set +x
TP_SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
. "$TP_SCRIPT_DIR/common.sh"
TP_MODE=--plan
TP_OPTIONS=()
TP_CI=false
for TP_ARG in "$@"; do
  case "$TP_ARG" in
    --install|--plan) TP_MODE=$TP_ARG ;;
    --with-jenkins|--with-monitoring|--with-nginx) TP_OPTIONS+=("$TP_ARG") ;;
    --with-ci) TP_CI=true ;;
    --help|-h)
      echo '用法：sudo bash scripts/server/bootstrap.sh --install [--with-jenkins] [--with-monitoring] [--with-nginx] [--with-ci]'
      echo '默认 --plan 只检查并显示计划；基础组件为 Docker/Compose、MySQL、Redis、Seata、RocketMQ、XXL-JOB。--with-ci 安装宿主机构建工具和专用账号。'
      exit 0 ;;
    *) die "未知参数：$TP_ARG" ;;
  esac
done
if [[ $TP_MODE == --plan ]]; then
  bash "$TP_SCRIPT_DIR/install-docker.sh" --plan
  echo "基础服务：MySQL 8.4、Redis 7.4、Seata 2.1.0、RocketMQ 5.3.4、XXL-JOB 3.2.0；可选项：${TP_OPTIONS[*]:-无}；构建环境：$TP_CI"
  echo '目录 /opt/tradepass；生成四套独立业务库账号和随机密码；安装过程不迁移历史业务数据、不启动业务服务。'
  echo '请按 Compose 内存配置和实际负载规划容量；全套基础设施、六个 JVM、监控与 CI 的资源分别计算。'
  exit 0
fi
require_root
bash "$TP_SCRIPT_DIR/install-docker.sh" --install
python3 "$TP_SCRIPT_DIR/services.py" up "${TP_OPTIONS[@]}"
if [[ $TP_CI == true ]]; then bash "$TP_SCRIPT_DIR/install-ci-agent.sh" --install; fi
echo '服务器基础安装完成；参见 docs/server-bootstrap.md 完成 Jenkins 初始化、数据库迁移和业务发布。'
