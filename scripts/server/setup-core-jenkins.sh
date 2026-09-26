#!/usr/bin/env bash
# One-time setup for the current CentOS Stream 9 host; never recreates application containers.
set -euo pipefail
set +x
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
MODE="${1:---plan}"
case "$MODE" in --plan|--install|--agent-only) ;; *) echo '用法：setup-core-jenkins.sh [--plan|--install|--agent-only]' >&2; exit 2 ;; esac
if [[ "$MODE" == --plan ]]; then
  echo '安装 Jenkins 控制器（127.0.0.1:18080）及宿主机 JDK 17/21、Maven、Git、Python/PyYAML。'
  echo '创建 tradepass-ci 构建账号（Docker 组）及两把专用 SSH 密钥，部署账号使用现有 root。'
  echo '复用已有 Docker；不停止或重建业务容器，不修改 Nacos/数据库/业务配置。'
  echo '已有 Jenkins 请运行 connect-core-jenkins.py --install，自动接入节点和发布任务。'
  exit 0
fi
[[ "$EUID" == 0 ]] || { echo '请使用 root 执行安装' >&2; exit 1; }
. /etc/os-release
[[ "${ID:-}" == centos && "${VERSION_ID%%.*}" == 9 ]] || {
  echo '本脚本面向当前 CentOS Stream 9；其他系统需要调整构建工具安装命令。' >&2; exit 1;
}
command -v dnf >/dev/null
docker info >/dev/null
docker compose version >/dev/null
command -v sshd >/dev/null
if [[ "$MODE" != --agent-only ]] && docker ps -a --format '{{.Names}} {{.Image}}' | awk '$2 ~ /^jenkins\/jenkins/ && $1 != "tradepass-jenkins-jenkins-1" {found=1} END {exit !found}'; then
  echo '发现另一套 Jenkins 容器，请先确认已有实例，不重复安装。' >&2
  exit 1
fi
dnf install -y java-17-openjdk-devel java-21-openjdk-devel maven git python3 python3-pyyaml openssh-clients
[[ -x /usr/lib/jvm/java-17-openjdk/bin/java && -x /usr/lib/jvm/java-21-openjdk/bin/java ]] || {
  echo '未找到 JDK 17/21 标准路径，请检查安装结果。' >&2; exit 1;
}
python3 -c 'import yaml'
getent group docker >/dev/null
if ! id tradepass-ci >/dev/null 2>&1; then
  useradd --create-home --user-group --shell /bin/bash tradepass-ci
  # No usable password; public-key-only login. Existing users are not changed here.
  usermod --password '*' tradepass-ci
fi
CI_HOME="$(getent passwd tradepass-ci | cut -d: -f6)"
[[ "$CI_HOME" == /home/tradepass-ci ]] || { echo '已有 tradepass-ci 的 home 不符合预期' >&2; exit 1; }
usermod -aG docker tradepass-ci
install -d -m 0750 -o tradepass-ci -g tradepass-ci /opt/tradepass/jenkins-agent
install -d -m 0700 /opt/tradepass/jenkins-private
install -d -m 0700 -o tradepass-ci -g tradepass-ci "$CI_HOME/.ssh"
install -d -m 0700 /root/.ssh
for role in ci deploy; do
  key="/opt/tradepass/jenkins-private/$role"
  if [[ ! -e "$key" && ! -e "$key.pub" ]]; then
    ssh-keygen -q -t ed25519 -N '' -C "tradepass-jenkins-$role" -f "$key"
  fi
  [[ -f "$key" && -f "$key.pub" && ! -L "$key" && ! -L "$key.pub" ]] || { echo 'SSH 密钥文件不完整，保留现有文件并退出' >&2; exit 1; }
  chmod 600 "$key"
  if [[ "$role" == ci ]]; then auth="$CI_HOME/.ssh/authorized_keys"; else auth=/root/.ssh/authorized_keys; fi
  [[ ! -L "$auth" ]] || { echo '拒绝修改符号链接 authorized_keys' >&2; exit 1; }
  touch "$auth"
  if ! grep -qxF -- "$(cat "$key.pub")" "$auth"; then printf '\n' >> "$auth"; cat "$key.pub" >> "$auth"; fi
  chmod 600 "$auth"
done
chown -R tradepass-ci:tradepass-ci "$CI_HOME/.ssh"
if command -v restorecon >/dev/null; then restorecon -RF "$CI_HOME/.ssh" /root/.ssh; fi
[[ -f /etc/ssh/ssh_host_ed25519_key.pub ]] || { echo '缺少服务器 ED25519 主机公钥' >&2; exit 1; }
HOST_KEY="$(awk '{print $1 " " $2}' /etc/ssh/ssh_host_ed25519_key.pub)"
printf 'host.docker.internal,127.0.0.1,124.221.190.63 %s\n' "$HOST_KEY" > /opt/tradepass/jenkins-private/known_hosts
chmod 600 /opt/tradepass/jenkins-private/known_hosts
# Test keys/permissions before starting the controller. Do not relax sshd policy on failure.
ssh -o BatchMode=yes -o StrictHostKeyChecking=yes -o UserKnownHostsFile=/opt/tradepass/jenkins-private/known_hosts \
  -i /opt/tradepass/jenkins-private/ci tradepass-ci@127.0.0.1 'set -e; docker info >/dev/null; /usr/lib/jvm/java-21-openjdk/bin/java -version'
ssh -o BatchMode=yes -o StrictHostKeyChecking=yes -o UserKnownHostsFile=/opt/tradepass/jenkins-private/known_hosts \
  -i /opt/tradepass/jenkins-private/deploy root@127.0.0.1 'set -e; python3 -c "import yaml"; docker info >/dev/null'
if [[ "$MODE" == --agent-only ]]; then
  echo '宿主构建节点工具和专用 SSH 密钥已准备好。'
  exit 0
fi
docker compose -f "$ROOT_DIR/deploy/jenkins/compose.yml" up -d
echo 'Jenkins 容器已启动，首次初始化可能需要几分钟。'
echo '私有凭据目录：/opt/tradepass/jenkins-private（未打印密钥）'
echo '构建节点：tradepass-ci；Java 21：/usr/lib/jvm/java-21-openjdk/bin/java'
echo '应用 JDK：/usr/lib/jvm/java-17-openjdk；Maven：/usr/share/maven'
echo '完成 Jenkins 初始化后，运行 connect-core-jenkins.py --install 接入节点和发布任务。'
