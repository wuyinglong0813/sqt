#!/usr/bin/env bash
# Optional host SSH build agent for the existing Jenkinsfile.
set -euo pipefail
set +x
TP_SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
. "$TP_SCRIPT_DIR/common.sh"
case ${1:---plan} in --plan|--install) TP_MODE=${1:---plan} ;; *) die '用法：install-ci-agent.sh [--plan|--install]' ;; esac
detect_system
if [[ $TP_MODE == --plan ]]; then
  echo '安装 Temurin JDK 17/21、Maven 3.9.11、Node 22.23.2、Python venv/PyYAML、Git、SSH server。'
  echo '创建 tradepass-ci / tradepass-deploy 账号并加入 docker 组（等同宿主机高权限）；仅供可信代码构建。'
  echo '工具位于 /opt/tradepass/tools；SSH 密钥、公钥和 Jenkins 凭据由你配置。'
  exit 0
fi
require_root
local_docker
docker info >/dev/null
[[ -f /opt/tradepass/staging/.env ]] || die '请先运行 bootstrap.sh --install，准备测试环境。'
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends ca-certificates curl gnupg git openssh-server python3 python3-venv xz-utils unzip fontconfig
TP_TMP=$(mktemp -d)
trap 'rm -rf -- "$TP_TMP"' EXIT
install -m 0755 -d /etc/apt/keyrings /opt/tradepass/tools
if ! installed temurin-17-jdk || ! installed temurin-21-jdk; then
  download https://packages.adoptium.net/artifactory/api/gpg/key/public "$TP_TMP/adoptium.asc"
  gpg --batch --yes --dearmor --output "$TP_TMP/adoptium.gpg" "$TP_TMP/adoptium.asc"
  install_config "$TP_TMP/adoptium.gpg" /etc/apt/keyrings/tradepass-adoptium.gpg
  cat > "$TP_TMP/adoptium.sources" <<EOF
Types: deb
URIs: https://packages.adoptium.net/artifactory/deb
Suites: $VERSION_CODENAME
Components: main
Architectures: $TP_ARCH
Signed-By: /etc/apt/keyrings/tradepass-adoptium.gpg
EOF
  if grep -Rqs 'packages.adoptium.net' /etc/apt/sources.list /etc/apt/sources.list.d 2>/dev/null; then
    [[ -f /etc/apt/sources.list.d/tradepass-adoptium.sources ]] || die '已有 Adoptium apt 源，请先核对；没有叠加配置。'
  fi
  install_config "$TP_TMP/adoptium.sources" /etc/apt/sources.list.d/tradepass-adoptium.sources
  apt-get update
  TP_JDK_PACKAGES=()
  installed temurin-17-jdk || TP_JDK_PACKAGES+=(temurin-17-jdk)
  installed temurin-21-jdk || TP_JDK_PACKAGES+=(temurin-21-jdk)
  apt-get install -y --no-install-recommends "${TP_JDK_PACKAGES[@]}"
fi
for TP_JDK in 17 21; do
  TP_JDK_DIR="/usr/lib/jvm/temurin-$TP_JDK-jdk-$TP_ARCH"
  [[ -x $TP_JDK_DIR/bin/java ]] || die "JDK 路径未找到：$TP_JDK_DIR"
  TP_LINK="/opt/tradepass/tools/jdk$TP_JDK"
  if [[ -e $TP_LINK || -L $TP_LINK ]]; then
    [[ $(readlink -f "$TP_LINK") == "$TP_JDK_DIR" ]] || die "保留已有 $TP_LINK；请检查工具路径。"
  else
    ln -s "$TP_JDK_DIR" "$TP_LINK"
  fi
done

TP_NODE_VERSION=22.23.2
case $TP_NODE_ARCH in
  x64) TP_NODE_SHA=d60acfe00a2932254bb0ad20e01b0d74397a0875595de719654b214f4b03f307 ;;
  arm64) TP_NODE_SHA=fff4078c5def658577f92c88db7db3bc0072924bfb93fe52c1e744a54e94abb8 ;;
esac
TP_NODE_DIR="/opt/tradepass/tools/node-v$TP_NODE_VERSION-linux-$TP_NODE_ARCH"
if [[ ! -d $TP_NODE_DIR ]]; then
  TP_NODE_FILE="node-v$TP_NODE_VERSION-linux-$TP_NODE_ARCH.tar.xz"
  download "https://nodejs.org/dist/v$TP_NODE_VERSION/$TP_NODE_FILE" "$TP_TMP/$TP_NODE_FILE"
  echo "$TP_NODE_SHA  $TP_TMP/$TP_NODE_FILE" | sha256sum --check --status
  tar -xJf "$TP_TMP/$TP_NODE_FILE" -C "$TP_TMP"
  mv "$TP_TMP/node-v$TP_NODE_VERSION-linux-$TP_NODE_ARCH" "$TP_NODE_DIR"
fi
[[ $("$TP_NODE_DIR/bin/node" --version) == "v$TP_NODE_VERSION" ]] || die 'Node 安装目录不完整，请检查后重试。'

TP_MAVEN_VERSION=3.9.11
TP_MAVEN_DIR="/opt/tradepass/tools/apache-maven-$TP_MAVEN_VERSION"
if [[ ! -d $TP_MAVEN_DIR ]]; then
  TP_MAVEN_FILE="apache-maven-$TP_MAVEN_VERSION-bin.tar.gz"
  download "https://archive.apache.org/dist/maven/maven-3/$TP_MAVEN_VERSION/binaries/$TP_MAVEN_FILE" "$TP_TMP/$TP_MAVEN_FILE"
  TP_MAVEN_SHA=bcfe4fe305c962ace56ac7b5fc7a08b87d5abd8b7e89027ab251069faebee516b0ded8961445d6d91ec1985dfe30f8153268843c89aa392733d1a3ec956c9978
  echo "$TP_MAVEN_SHA  $TP_TMP/$TP_MAVEN_FILE" | sha512sum --check --status
  tar -xzf "$TP_TMP/$TP_MAVEN_FILE" -C "$TP_TMP"
  mv "$TP_TMP/apache-maven-$TP_MAVEN_VERSION" "$TP_MAVEN_DIR"
fi
[[ -x $TP_MAVEN_DIR/bin/mvn ]] || die 'Maven 安装目录不完整，请检查后重试。'

TP_VENV=/opt/tradepass/tools/python
if [[ ! -x $TP_VENV/bin/python3 ]]; then python3 -m venv "$TP_VENV"; fi
if ! "$TP_VENV/bin/python3" -c 'import yaml; assert yaml.__version__ == "6.0.3"' 2>/dev/null; then
  "$TP_VENV/bin/python3" -m pip install --disable-pip-version-check -r "$TP_SCRIPT_DIR/../ci/requirements.txt"
fi

for TP_USER in tradepass-ci tradepass-deploy; do
  TP_USER_HOME="/home/$TP_USER"
  if id "$TP_USER" >/dev/null 2>&1; then
    [[ $(getent passwd "$TP_USER" | cut -d: -f6) == "$TP_USER_HOME" ]] || die "已有 $TP_USER 使用不同 home；请人工核对。"
  else
    useradd --create-home --user-group --shell /bin/bash "$TP_USER"
    # A disabled password with an unlocked account permits public-key-only SSH.
    usermod --password '*' "$TP_USER"
  fi
  usermod -aG docker "$TP_USER"
  install -d -m 0700 -o "$TP_USER" -g "$TP_USER" "$TP_USER_HOME/.ssh"
  if [[ ! -e $TP_USER_HOME/.ssh/authorized_keys ]]; then
    install -m 0600 -o "$TP_USER" -g "$TP_USER" /dev/null "$TP_USER_HOME/.ssh/authorized_keys"
  fi
done
install -d -m 0750 -o tradepass-ci -g tradepass-ci /home/tradepass-ci/agent
# Preserve existing releases; only the directory and initial configuration need ownership.
chown tradepass-deploy:tradepass-deploy /opt/tradepass/staging /opt/tradepass/staging/.env
chmod 0750 /opt/tradepass/staging
chmod 0600 /opt/tradepass/staging/.env
systemctl enable --now ssh
cat > "$TP_TMP/ci-agent.txt" <<EOF
Jenkins SSH Agent：
  Host: host.docker.internal (controller on this server)
  User: tradepass-ci
  Remote root directory: /home/tradepass-ci/agent
  Labels: tradepass-ci
  Executors: 1
  JavaPath: /opt/tradepass/tools/jdk21/bin/java
  Environment Variables -> PATH+TRADEPASS:
    $TP_VENV/bin:$TP_NODE_DIR/bin:/usr/local/bin:/usr/bin:/bin
Jenkins Global Tools (disable automatic installation):
  JDK name: tradepass-jdk17; JAVA_HOME: /opt/tradepass/tools/jdk17
  Maven name: tradepass-maven; MAVEN_HOME: $TP_MAVEN_DIR
Deployment user: tradepass-deploy; deployment root: /opt/tradepass/staging
Add your Jenkins SSH public keys to each account's .ssh/authorized_keys.
Use verified host-key checking and configure Jenkins credentials; no keys were generated or uploaded.
EOF
install_config "$TP_TMP/ci-agent.txt" /opt/tradepass/tools/ci-agent.txt
JAVA_HOME=/opt/tradepass/tools/jdk17 "$TP_MAVEN_DIR/bin/mvn" --version
"/opt/tradepass/tools/jdk21/bin/java" -version
"$TP_NODE_DIR/bin/node" --version
"$TP_VENV/bin/python3" -c 'import yaml; print("PyYAML", yaml.__version__)'
runuser -u tradepass-ci -- docker -H unix:///var/run/docker.sock version --format 'Agent Docker: {{.Server.Version}}'
cat /opt/tradepass/tools/ci-agent.txt
