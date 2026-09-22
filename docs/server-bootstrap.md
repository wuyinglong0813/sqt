# 从空服务器安装 TradePass 运行环境

> 当前服务器部署已升级为四库独立服务、Seata、RocketMQ 和 XXL-JOB，操作以 [分库切换说明](server-microservices-cutover.md) 为准。下文记录此前阶段；共享库和旧环境变量示例不适用于新的独立服务。

> 已经安装 Docker、希望直接管理 YAML 的服务器，优先使用 [infra + service 两份静态 Compose](../deploy/server/README.md)。下文 Python/安装器属于可选的配置生成与安装方式，不是容器运行的依赖。

自动安装 Docker 和宿主机构建工具适用于 **Ubuntu 22.04 / 24.04、Debian 12 / 13，amd64 / arm64，systemd**。**CentOS Stream 9 已装好 Docker/Compose 时，使用下方的“已有 Docker 的 CentOS 服务器”入口。** 安装在服务器执行；本地 Mac 只用于打包、上传。

这套脚本准备单机 Docker Compose 测试环境，接入现有 Jenkins 发布流程。**业务代码和业务事务没有修改。新 MySQL 只创建空的 `tradepass_staging` 数据库；不会迁移、清空或导入业务数据，也不会启动六个业务服务。**

## 1. 安装哪些东西

| 组件 | 选择方式 | 用途 |
| --- | --- | --- |
| Docker Engine、Compose、Buildx、Python 3 | 基础安装 | 运行容器、执行部署脚本 |
| MySQL 8.4、Redis 7.4 | 基础安装 | 测试数据库、缓存服务，持久化数据卷 |
| Jenkins 控制器 | `--with-jenkins` | 持续集成界面、流水线调度 |
| JDK 17 / 21、Maven、Node、Python/PyYAML、Git、SSH | `--with-ci` | Jenkins 宿主机 SSH 构建节点，以及部署账号 |
| Prometheus、Grafana、Alertmanager、Node Exporter | `--with-monitoring` | JVM / HTTP 指标、主机 CPU / 内存 / 磁盘、告警规则 |
| Nginx | `--with-nginx` | 转发到 Gateway，先通过本机端口验证 |

Redis 安装后，业务配置的 `TRADEPASS_REDIS_ENABLED` 仍为 `false`；需要接入时再显式启用。Alertmanager 默认在页面展示告警，接收人、Webhook / 邮件配置完成前不会向外发送通知。

K8s 本轮不自动安装，现有 K8s 清单继续保留。Nacos、RocketMQ、Seata、XXL-JOB、Sentinel、ELK、SkyWalking 尚需对应业务接入与资源规划，当前安装器不启动这些组件。OSS 是外部对象存储服务，仍需账号、Bucket 和后端适配。

容量参考是部署规划估算，并非性能承诺：

- 仅基础服务：2 核 / 4 GiB 起。
- 六服务加监控：建议 4 核 / 8 GiB 起。
- 同一台机器同时跑 Jenkins、完整 CI 和应用：建议 8 核 / 16 GiB 起；Jenkins 构建节点执行器先设为 1。磁盘建议至少预留 50 GiB，按镜像、数据库和备份增长调整。

## 2. 上传脚本包

### 已有 Docker 的 CentOS 服务器

当前服务器是 CentOS Stream 9，4 核、3.6 GiB 内存，Docker 和 Compose 可用。先运行 MySQL、Redis，创建空的独立测试库；六个业务进程的内存配置及监控按后续实测确定。完整 Jenkins 构建和全套监控的同机运行尚未验收。

1. 将本地生成的 `dist/tradepass-server-bootstrap.tar.gz` 上传到服务器 `/root/`。Mac 本地可执行下面“本地打包”中的 `scp`，也可用已有 SFTP 工具上传。
2. 在服务器的 root 终端执行：

```bash
dnf install -y python3 tar
mkdir -p /root/tradepass-setup
tar -xzf /root/tradepass-server-bootstrap.tar.gz -C /root/tradepass-setup
cd /root/tradepass-setup/tradepass-server-bootstrap
python3 scripts/server/services.py up
```

`services.py` 使用 Python 标准库和已有 Docker，不调用 apt、不安装或重启 Docker。无选项时只拉取并启动 MySQL、Redis；等待两者 `healthy` 才报告成功。首次生成配置后会保留随机密码、镜像 digest、应用 `.env` 及数据卷。基础服务 Compose 位于服务器 `/opt/tradepass/infra/compose.json`。

查看结果：

```bash
python3 /root/tradepass-setup/tradepass-server-bootstrap/scripts/server/services.py status
```

把状态输出用于下一步部署确认；无需发送 `.env` 或密码。这个阶段只检查两个基础服务的健康状态，业务数据库表仍需后续执行 V36 迁移。

新生成的配置将本项目的配置和密码文件以只读 bind mount 挂入容器，并设置 SELinux 的 `z` 标签，避免 CentOS 开启 SELinux 时容器读不到文件。只标记明确的项目文件及配置子目录，主机根目录挂载不加标签；已有手工配置仍不会被覆盖。依据 [Docker Compose 挂载说明](https://docs.docker.com/reference/compose-file/services/#volumes)。本次 25 项脚本测试通过，覆盖挂载配置、密码保留与部署逻辑；本机临时容器验证未获授权，没有完成新配置的容器启动实测或 CentOS SELinux enforcing 验证，目标机状态仍需以上命令确认。

### 本地打包与 Ubuntu/Debian 自动安装

这批文件尚未推送 GitHub，可以先在本地仓库根目录打包：

```bash
python3 scripts/server/package.py
scp dist/tradepass-server-bootstrap.tar.gz 你的SSH用户@服务器IP:~/
```

通过 SSH 登录服务器，执行：

```bash
tar -xzf ~/tradepass-server-bootstrap.tar.gz
cd tradepass-server-bootstrap

# 只检查系统并查看安装计划，无须 sudo，不会安装服务
bash scripts/server/bootstrap.sh --plan --with-jenkins --with-monitoring --with-nginx --with-ci

# 只安装 Docker、Compose、MySQL、Redis
sudo bash scripts/server/bootstrap.sh --install
```

服务器容量合适时，完整安装本阶段组件：

```bash
sudo bash scripts/server/bootstrap.sh --install \
  --with-jenkins --with-monitoring --with-nginx --with-ci
```

也可以先只安装 Docker：

```bash
sudo bash scripts/server/install-docker.sh --install
```

`--with-ci` 会创建 `tradepass-ci` 和 `tradepass-deploy`，加入 Docker 组，并启动系统 SSH 服务。Docker 组具备宿主机高权限，构建节点只运行你信任的仓库代码。安装器不修改现有 SSH 登录策略或防火墙，不向账号写入任何 SSH 公钥。

软件来自 Docker / Adoptium 官方 apt 源、Node / Maven 官方下载地址及官方容器镜像。需要服务器能够访问这些站点和镜像仓库。下载失败会停止；修复网络后重跑。没有内置第三方镜像加速器，也不关闭 TLS 校验。

## 3. 目录、凭据和重复执行

```text
/opt/tradepass/
  infra/                     root-only，基础服务配置
    .env                     首次生成的随机密码和安装标识，0600
    compose.json             容器配置；重复执行保留
    images.lock.json         首次拉取后固定的镜像 digest
    secrets/                 容器所需密码文件
    redis.conf
    nginx.conf
    monitoring/
  staging/
    .env                     应用测试环境配置，0600
    releases/                后续由 Jenkins 发布包创建
  tools/                     --with-ci 安装的工具和 ci-agent.txt
```

- 密码使用安全随机数生成，长度 64 个十六进制字符，没有通用默认密码；不会在安装日志打印。
- 第二次运行保留密码、应用 `.env`、监控配置和卷数据。镜像锁文件使重跑继续使用原镜像版本；安装器不是组件升级器。
- 已有正常 Docker 保留原版本；缺少 Compose / Buildx、已有冲突运行时或历史 Docker 数据时，提示管理员核对，不执行自动卸载。
- 如果数据卷存在但原始配置/密码丢失，会停止。恢复备份中的 `infra` 目录后再继续，不能重新生成密码来连接旧卷。
- MySQL / Redis / Jenkins / 监控数据存入 Docker 命名卷，不能用 `down -v`、`docker volume prune` 清理这些业务环境。备份应包含数据库和其他需要恢复的数据卷，以及受保护的 `infra`、`staging/.env`，并另存异机副本。
- 新服务镜像首次锁定前按脚本中的版本或维护分支拉取；以后升级要先备份、验证，再单独更新镜像锁。重跑不会自动升级已锁定的基础组件。

运行状态：

```bash
sudo python3 scripts/server/services.py status
```

查看管理员初始凭据（仅在你自己的安全终端操作）：

```bash
# MySQL、Redis、Grafana 的初始随机凭据
sudo cat /opt/tradepass/infra/.env

# Jenkins 安装向导初始密码
sudo docker compose --project-name tradepass-infra \
  --env-file /opt/tradepass/infra/.env \
  -f /opt/tradepass/infra/compose.json \
  exec -T jenkins cat /var/jenkins_home/secrets/initialAdminPassword
```

Grafana 用户名为 `admin`，初始密码对应 `.env` 的 `GRAFANA_ADMIN_PASSWORD`。改完 Grafana 登录密码后以你修改的密码为准。MySQL 和 Grafana 的初始化密码只在空数据卷第一次启动时生效，不能靠修改 `.env` 给已有数据库改密码。

## 4. 访问页面

在你的本地电脑执行 SSH 隧道，替换登录用户和服务器 IP：

```bash
ssh -N \
  -L 18080:127.0.0.1:18080 \
  -L 13000:127.0.0.1:13000 \
  -L 19090:127.0.0.1:19090 \
  -L 19093:127.0.0.1:19093 \
  -L 18000:127.0.0.1:18000 \
  你的SSH用户@服务器IP
```

| 页面 | 本地浏览器地址 |
| --- | --- |
| Jenkins | `http://127.0.0.1:18080` |
| Grafana | `http://127.0.0.1:13000` |
| Prometheus | `http://127.0.0.1:19090` |
| Alertmanager | `http://127.0.0.1:19093` |
| Nginx → Gateway | `http://127.0.0.1:18000`，业务服务发布前返回 502；`/_nginx_health` 只检查 Nginx |

所有管理页面仅绑定服务器 `127.0.0.1`。MySQL、Redis、Node Exporter 不映射宿主机端口，通过容器网络访问。不需要把 3306、6379、8080、9090 等端口开放到公网。

Nginx 当前是验证用 HTTP 入口。正式接入小程序需要域名、证书、HTTPS、云安全组和微信合法域名配置；有这些信息后再接入公网 443。这里不会自动签发证书或改防火墙。

## 5. Jenkins 最后配置

`--with-ci` 安装完会生成 `/opt/tradepass/tools/ci-agent.txt`，包含你这台服务器的准确路径。Jenkins 界面仍需配置 GitHub 授权和你自己的 SSH 密钥：

1. 完成管理员向导；安装 `deploy/jenkins/plugins.txt` 中的插件，将控制器内置节点执行器设为 0。
2. 将 Jenkins 用于 SSH Agent 的公钥写入 `/home/tradepass-ci/.ssh/authorized_keys`；将发布使用的公钥写入 `/home/tradepass-deploy/.ssh/authorized_keys`。保持所属账号正确，文件权限为 0600。
3. 创建 SSH Agent，宿主机地址 `host.docker.internal`，账号 `tradepass-ci`，标签 `tradepass-ci`，执行器 1，远端目录 `/home/tradepass-ci/agent`，启动 Java 路径 `/opt/tradepass/tools/jdk21/bin/java`。SSH 主机密钥通过可信渠道核对后配置。
4. 在节点属性 Environment Variables 中添加 `PATH+TRADEPASS`，其值照抄 `ci-agent.txt`。这一步把 Node 和带 PyYAML 的 Python venv 加入构建 PATH；不修改系统 Python。
5. 全局工具的 JDK 命名为 `tradepass-jdk17`，路径 `/opt/tradepass/tools/jdk17`；Maven 命名为 `tradepass-maven`，路径 `/opt/tradepass/tools/apache-maven-3.9.11`。关闭自动安装。
6. 将当前重构及流水线代码提交到 GitHub 后，创建 Multibranch Pipeline，脚本路径 `Jenkinsfile`。按 [CI/CD 手册](cicd-deployment.md) 设置 GitHub、镜像推送、部署 SSH、已核验 known_hosts 等凭据，先运行 `ACTION=verify`。

Jenkins 控制器没有挂载 Docker socket；构建由 SSH Agent 执行。构建时 Java 仍为 17，Jenkins Agent 的 Java 为 21，二者不同。没有公网 HTTPS 时先手动扫描仓库，后续再接 GitHub Webhook。

## 6. 接入六个业务服务

服务器 `.env` 已为新建测试库填写以下内容：

```text
TRADEPASS_NETWORK_NAME=tradepass-staging-services
TRADEPASS_NETWORK_EXTERNAL=true
TRADEPASS_DATABASE_URL=jdbc:mysql://mysql:3306/tradepass_staging?...参数...
REDIS_HOST=redis
REDIS_PORT=6379
```

同一 Docker 网络中，发布的服务通过 `mysql`、`redis` 这些名称连接基础组件；Prometheus 通过 `identity:8081` 等内部地址采集指标。发布包已支持这两个网络参数；未配置时仍使用各自 Compose 项目的独立网络。

**先给独立测试库执行已核验的 V36 迁移或恢复经过脱敏的测试备份，再运行 Jenkins 部署。** 本安装器、CI/CD 发布器都不代替业务数据库迁移。新数据库为空时应用不能正常启动，属于预期保护边界。

微信 App ID / Secret、法大大参数、正式对象存储等继续在服务器配置，不提交 GitHub。当前第一阶段 CD 仍只允许 `staging`；生产上线条件见 [CI/CD 手册](cicd-deployment.md)。

监控在应用尚未部署时出现六个应用 target 为 DOWN 是正常现象。已有 Grafana 应用仪表盘展示 JVM、HTTP 指标；主机 CPU / 内存 / 磁盘可在 Prometheus / Grafana Explore 查询 `node_*` 指标。Node Exporter 使用私有容器网络，已关闭会误报容器网卡为宿主机网卡的网络统计采集器。日志当前使用 Docker 日志轮转，集中日志检索和链路追踪另行接入。

## 7. 安装来源与验证范围

安装方式依据 [Docker Ubuntu](https://docs.docker.com/engine/install/ubuntu/)、[Docker Debian](https://docs.docker.com/engine/install/debian/)、[Adoptium](https://adoptium.net/installation/linux/) 官方文档。Node 下载校验值来自 [Node 22.23.2](https://nodejs.org/en/blog/release/v22.23.2)，Maven 校验值来自 [Maven 3.9.11 发布目录](https://archive.apache.org/dist/maven/maven-3/3.9.11/binaries/)。监控版本来源为 [Prometheus 下载列表](https://prometheus.io/download/) 和 [Grafana 13.2.1](https://github.com/grafana/grafana/releases/tag/v13.2.1)。

本地验证包括 24 项部署脚本测试、Shell/Python 语法、Compose 配置解析、发布网络插值、安装包独立解压与配置生成。临时 Docker 环境已实测 MySQL/Redis 启动、内网名称连接和认证、空库状态、重跑保留凭据及镜像 digest，测试容器和数据卷已清理。可选组件官方镜像已成功拉取，并检查了 Nginx 配置、新版 Prometheus 配置及 7 条告警规则、Grafana 运行用户的配置/凭据读取权限、Jenkins Java 21 与健康检查所需命令。这里没有服务器 SSH 信息，尚未在你的真实 Linux 服务器执行 apt 安装、Jenkins 初始化或业务发布。
