# 三进程部署：Nacos + RocketMQ，无 Seata

应用为 gateway（1110）、identity（1111）、business（1112）。business 装配 contract、trade、settlement、file 模块；同进程调用保留模块 API，使用同一个 DataSource 和 Spring 本地事务。原 HTTP 路径不变。核心数据在新 `_business` 库，identity 继续使用 `_identity` 库。对象存储和法大大外部调用仍有自己的持久化恢复语义，不属于数据库事务。

## 配置和资源

- 复制 `deploy/server/.env.core.example` 到私有 `.env.core`，权限 600。已有服务器必须沿用 MySQL root、identity、Redis 密码，以及 MySQL/MQ 原项目名、卷名、网络。先用旧 Compose 的 `config` 和 `docker inspect` 核对实际卷，尤其是之前使用过 `-p` 的环境。
- `.env.core` 使用宿主机可达地址。仅支持 Linux 单机 host 网络；Nacos 注册 IP 为 127.0.0.1。多机不能直接沿用此配置。
- business 使用新的 `BUSINESS_DATABASE_URL/DB_USERNAME/DB_PASSWORD`，不再读取 contract/trade/settlement 三组数据库凭据。数据库名必须以 `_business` 结尾，Flyway 不允许对非空旧库自动 baseline。
- Nacos 配置组默认 `TRADEPASS_CORE`，Data ID 为 `tradepass-common.yaml`、`tradepass-gateway.yaml`、`tradepass-identity.yaml`、`tradepass-business.yaml`。三个服务均启用 `nacos,core`，business 另外启用 `messaging`。配置启动时读取，修改后重启生效。
- RocketMQ 使用现有 remoting 客户端，单 NameServer 和单 Broker，无 Proxy 和控制台；保留原 MQ 持久卷。默认回调 topic/group 与六进程版一致，切换期间只允许一套消费者运行。自定义 topic 时，必须先在 Broker 创建对应 topic。
- Seata、XXL-JOB、SkyWalking agent 关闭。回调由 MQ 推送，数据库事件、消费幂等、领取租约及每 30 秒本地恢复继续保留。MQ 不是核心事务的替代品。
- 应用及常驻基础容器内存上限合计约 3488 MiB，给系统及 Nginx 留约 608 MiB；这是预算，不是实测容量。按低并发验证 PDF、Excel、文件上传峰值和 MQ 积压。不要同机运行 Jenkins 构建、ELK 或 SkyWalking。OOM、GC 或 IO 压力持续时应增加内存或移出中间件。
- 保持已有 `restart: "no"` 应用策略；启动和机器重启后需要显式拉起业务。检查 `docker stats --no-stream` 及容器 OOM 状态。

## 构建

仓库根目录执行；镜像在构建机生成，不在 4G 业务服务器上并发构建：

```bash
mvn -B -DskipTests package
mvn -B -f tools/database-migrator/pom.xml -DskipTests package
docker build -f tradepass-gateway/Dockerfile -t tradepass-gateway:local .
docker build -f tradepass-module-identity/tradepass-module-identity-server/Dockerfile -t tradepass-identity:local .
docker build -f tradepass-business/Dockerfile -t tradepass-business:local .
```

三个镜像及迁移 JAR 一起交付，`.env.core` 的 tag 与镜像保持一致。原六服务发布脚本仍是六服务发布通道，不能用于本模式。服务器安装包包含本模式的 Compose、配置模板和操作手册。

## 新环境

在 `deploy/server` 执行：

```bash
docker network create tradepass-server
docker compose --env-file .env.core -f infra.core.compose.yml up -d --wait --wait-timeout 360
```

如果服务器已有单独运行的 Nacos，复用其地址和账号，避免重复占用端口。此时基础设施命令明确选择服务：`docker compose --env-file .env.core -f infra.core.compose.yml up -d --wait mysql redis rocketmq-topic-init`，它会按依赖启动 Broker/NameServer；不要再启动本文件中的 nacos。

MySQL 空卷自动创建 identity/business 库及独立账号。Nacos 只绑定回环地址，通过 SSH 隧道访问控制台，先设置私有管理员密码，再填入 `.env.core` 的 `NACOS_PASSWORD`。`NACOS_AUTH_TOKEN` 和 `NACOS_AUTH_IDENTITY_VALUE` 是服务端鉴权配置，不会自动修改管理员密码。

加载私有环境并创建缺失的 Data ID（脚本保留已有配置，不覆盖）：

```bash
set -a
source .env.core
set +a
python3 ../../scripts/server/init-core-nacos.py
docker compose --env-file .env.core -f yudao.core.compose.yml up -d --no-build --wait --wait-timeout 360
```

不要在 Nacos 继续导入六进程版的地址配置。核心模块的 Feign 名称为 `tradepass-business`，identity 为 `tradepass-identity`；网关保留原路由分类，但四个核心领域都路由到 `lb://tradepass-business`。

HTTPS 可继续使用宿主机已有 Nginx，将上游改为 `127.0.0.1:1110`。若使用仓库内 Nginx，设置 `TRADEPASS_TLS_DIRECTORY` 后运行 `docker compose --env-file .env.core -f edge.core.compose.yml up -d`。不要同时运行旧 edge 配置；它的 `gateway:8080` 上游只适用于旧 bridge 网络。三进程应用端口仅监听回环地址。

## 已有四库环境迁移

1. 备份数据库、原镜像版本和配置，暂停入口业务写入，停止外部任务，等待 Seata 全局事务完成（包括回滚重试、各库 `undo_log` 清空）。停止六个旧应用。保留 MySQL、Redis 和 MQ 数据卷。
旧 yudao 业务的停止命令为 `docker compose --env-file .env -f yudao.compose.yml stop`；bridge 部署则使用实际的 `service.compose.yml`。新业务使用独立 Compose 容器名，避免与旧的已停止容器冲突。

2. 使用旧 Compose 停掉 `seata`、`xxl-job-admin`；另行停止已部署的 ELK/SkyWalking。不要用 `down -v`，不要删除旧数据库。启动 core infra 时保持原 project 名和卷映射；不要使用 `--remove-orphans`。
3. 已存在的 MySQL 数据卷不会执行初始化脚本。新建空 business 库和独立账号（下面命令只创建 business，已存在时失败，不覆盖）：

```bash
docker compose --env-file .env.core -f infra.core.compose.yml up -d --wait --wait-timeout 360
docker compose --env-file .env.core -f infra.core.compose.yml exec -T mysql bash /docker-entrypoint-initdb.d/11-core-databases.sh --business-only
```

4. 在私有迁移环境文件设置下列环境变量，所有 JDBC URL 使用执行迁移机器可达的地址：

```text
SOURCE_CONTRACT_DATABASE_URL / SOURCE_CONTRACT_DB_USERNAME / SOURCE_CONTRACT_DB_PASSWORD
SOURCE_TRADE_DATABASE_URL / SOURCE_TRADE_DB_USERNAME / SOURCE_TRADE_DB_PASSWORD
SOURCE_SETTLEMENT_DATABASE_URL / SOURCE_SETTLEMENT_DB_USERNAME / SOURCE_SETTLEMENT_DB_PASSWORD
TARGET_BUSINESS_DATABASE_URL / TARGET_BUSINESS_DB_USERNAME / TARGET_BUSINESS_DB_PASSWORD
```

加载环境后，先检查计划；确认所有源和目标写入均已暂停，再执行复制：

```bash
java -jar tools/database-migrator/target/tradepass-database-migrator-0.1.0-SNAPSHOT.jar --merge-business --plan
export TRADEPASS_CUTOVER_WRITES_PAUSED=true
java -jar tools/database-migrator/target/tradepass-database-migrator-0.1.0-SNAPSHOT.jar --merge-business --apply
```

工具只接受已审核的 owned V1 源表清单及空目标库；源连接只读；保留 ID、金额、二进制附件和三个库的审计记录，逐行回读比较内容及行数，检查合并后外键，全部通过后才提交目标业务数据。重复审计 ID 会报错，不会静默覆盖。identity 库不迁移。

MySQL DDL 不可随数据事务回滚：复制失败后，新目标可能保留空表和 Flyway 历史；原库不变。排查错误后使用另一个空 `_business` 库重试，不要清理原库。不得在原业务库运行此工具。

5. 按新环境步骤初始化 Nacos 配置，启动三进程。验证登录、企业切换、合同、收货库存对账、文件与法大大回调、重复消息和错误路径后再开放写入。旧业务容器与新 business 不得同时消费同一回调组。

## 回退与验证

开放业务写入前，可停止新应用并回到旧镜像和原四库（需恢复 Seata、旧消费/调度配置）。开放写入后，新 business 已产生新数据，不能直接切回旧库；需要数据回流方案。

本地完整验证：`bash scripts/ci/verify-core.sh`。脚本创建隔离 MySQL、Nacos 和 RocketMQ，不启动 Seata。复用原 API 基线和业务测试，并验证四库到统一库迁移、三进程、Nacos 注册配置/路由、MQ 消费、重复事件、库存/结算失败回滚及合同更新后的后续失败回滚。真实法大大/OSS 凭据和服务器 4G 内存负载需要部署环境单独验收。
