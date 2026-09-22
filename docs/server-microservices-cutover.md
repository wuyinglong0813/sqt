# 服务器微服务与分库切换

## 服务与数据所有权

采用 identity、contract、trade、settlement、file 和 gateway 六个独立进程。四个业务服务分别连接名称以 `_identity`、`_contract`、`_trade`、`_settlement` 结尾的数据库，使用各自的 MySQL 账号；file 不连接数据库。表归属以 `deploy/database/table-ownership.json` 为准。

各服务只能扫描自己的 Mapper。跨服务调用通过显式的 Operations / Reader 接口与 OpenFeign 适配器完成，保留原 HTTP 路径、JSON 数据结构和鉴权方式。内部接口校验服务密钥，并传递已经验证的用户和企业上下文；外部请求携带的事务标识会被移除。拆分服务启动时强制检查远程依赖存在，避免回落到兼容单体的跨表 SQL。

原有 Spring 事务在拆分模式下由 Seata AT 协调；本地隔离、回滚条件和 `noRollbackFor` 保持原配置。原来独立提交的法大大创建意图暂时离开全局事务，保留故障恢复依据。关键合同状态与双边操作的锁定查询通过数据库锁及 Seata 全局行锁协调。Seata AT 的普通读隔离与单库事务不同，迁移验收必须包含并发查询、重复提交、下游异常和补偿场景。

## 旧库迁移

1. 源库必须已完成原 V1–V36，原迁移脚本保持原样。准备四个全新的空数据库及只授权对应库的账号，完成备份，并停止所有旧业务写入进程和回调入口。
2. `mvn -f tools/database-migrator/pom.xml -DskipTests package` 构建迁移工具。用受保护的环境变量配置 `SOURCE_DATABASE_URL/SOURCE_DB_USERNAME/SOURCE_DB_PASSWORD` 和四组 `TARGET_<ROLE>_DATABASE_URL/TARGET_<ROLE>_DB_USERNAME/TARGET_<ROLE>_DB_PASSWORD`。
3. 运行 `java -jar tools/database-migrator/target/tradepass-database-migrator-0.1.0-SNAPSHOT.jar --plan`。此步骤只读，检查表清单和行数，不建表。
4. 确认写入已停止后，设置 `TRADEPASS_CUTOVER_WRITES_PAUSED=true`，以 `--apply` 运行同一个工具。目标执行各自的 Flyway 基线，复制原 ID、字段和文件 BLOB，按主键排序核对行数和 SHA-256；审计记录按业务类型分配，未知历史类型归 identity。
5. 目标提交依次完成，源库始终只读。工具不是在线同步器：断电可能留下部分已提交目标。失败后保留日志，准备新的空目标库重新迁移，禁止清空已有业务库或重新开放旧库写入后继续使用之前的拷贝。
6. 启动分库服务，在接入真实流量前验证登录、合同、审批、收货库存、结算、历史文件读取与故障回滚。正式切流后不得把旧单体直接接到拆分库；回退须重新制定数据回流方案。

## 单机部署

`deploy/server/.env.example` 列出了独立账号及中间件参数。MySQL 首次初始化要求各数据库密码和 XXL-JOB 管理密码分别使用 64 位随机十六进制值。已有 MySQL 数据目录不会自动执行初始化脚本，也不会改动旧库。

新环境先创建指定的私有 Docker 网络，使用 `deploy/server/infra.compose.yml --profile mq --profile jobs` 启动 MySQL、Redis、Seata 2.1.0、RocketMQ 和 XXL-JOB。部署脚本会创建回调 topic、消费组及每 30 秒一次的 `fadadaCallbackRecovery` 任务；执行器名为 `tradepass-contract`。XXL-JOB 后台账号为 `tradepass`，密码来自 `XXL_JOB_ADMIN_PASSWORD`；执行器和后台必须配置同一 `XXL_JOB_ACCESS_TOKEN`。

使用 `deploy/server/service.compose.yml` 启动六个业务镜像，合同服务默认启用 `messaging,jobs`。消息只包含已持久化回调 ID，原数据库事件认领、重试和恢复规则继续执行；消息发送失败保留持久化事件交给恢复任务。关闭 jobs 配置时，自动恢复原来的本地 30 秒调度。使用自定义 topic/消费组时，应同步修改基础设施初始化命令。

业务数据库和中间件端口保持私有。`deploy/server/edge.compose.yml` 使用提供的 TLS 证书将 HTTPS 转到网关。Prometheus、Grafana、Alertmanager 配置仍在 `deploy/microservices/monitoring`，告警接收目标由环境管理者配置。

Nacos 和 Sentinel 已有应用 profile、持久化规则与网关接入：按 `deploy/microservices/nacos/README.md` 导入配置，并将所有服务的 profile 设为 `observability,nacos,sentinel`，合同服务另外保留 `messaging,jobs`。每个环境使用独立 namespace/group 和凭据。

### OSS 与历史文件

服务器启用文件存储时，所有业务进程和 file 统一设置 `TRADEPASS_STORAGE_PROVIDER=aliyun-oss`、`TRADEPASS_STORAGE_ENABLED=true`、`TRADEPASS_STORAGE_REQUIRED=true`。仅 file 配置 `OSS_ENDPOINT`（HTTPS）、`OSS_REGION`、`OSS_BUCKET`、`OSS_ACCESS_KEY_ID`、`OSS_ACCESS_KEY_SECRET`，临时凭据另加 `OSS_SESSION_TOKEN`。示例中关闭存储仅用于无云凭据的初始启动检查。

OSS 适配器保留原文件引用、长度和摘要校验；使用私有 ACL、AES256 服务端加密和禁止同名覆盖。桶必须从未启用版本控制，因为 OSS 的版本控制会改变禁止覆盖的语义；适配器会检查并拒绝已开启或暂停版本控制的桶。凭据只需目标前缀的写入、读取及桶版本状态读取权限。

历史 MySQL BLOB 随所属业务表迁移；历史 COS 对象保留原引用，不自动搬迁。需要读取旧 COS 时，仅 file 配置 `TRADEPASS_LEGACY_COS_BUCKET/REGION/KEY_PREFIX/SECRET_ID/SECRET_KEY`，使用只读凭据；新写入仍进入 OSS。旧 COS 桶和新 OSS 桶名称必须不同。环境变量中的临时凭据到期后通过部署更新进程，当前没有自动刷新工作负载身份。

## Kubernetes、镜像发布和 GitOps

`scripts/ci/release.py` 输出按摘要固定的六个镜像及 Compose/Kubernetes 发布包；`scripts/ci/helm_values.py` 把同一个 release.json 转为 Helm 发布 values。Harbor 或托管仓库由镜像前缀指定。GitHub Actions 的 backend-release 工作流在整体验证后推送 GHCR；现有 Jenkins 脚本仍可使用。

Helm chart 位于 `deploy/helm/tradepass`，Argo CD Application 示例位于 `deploy/argocd`。先按 `deploy/k8s` 的独立 env 示例创建 Secret，再配置中间件私网地址、镜像摘要、提交版本及 secretRevision。修改 Secret 后递增 secretRevision 触发重建。不要把真实凭据提交进 Git。

业务服务使用 StatefulSet，按角色分配 0–5、6–11、12–17、18–23、24–29 的 Snowflake worker 范围。每个服务最多 6 个副本；每个同时写入相同数据的环境使用独立 datacenter ID。禁止强制删除仍在运行的 Pod 或在另一套部署复用同一 datacenter/worker 组合。网关可独立扩容。XXL-JOB 后台需以 `app.kubernetes.io/component=xxl-job-admin` 标签访问合同执行器 9998 端口。

## 日志、调用链和诊断

应用输出 JSON 日志。`observability/extended.compose.yml` 提供 Elasticsearch、Logstash、Kibana、Filebeat 和 SkyWalking；Filebeat 只采集 Compose 项目名以 `tradepass-` 开头的容器日志，不挂载 Docker socket。此采集配置用于 Linux Docker 主机。日志索引保留 14 天，控制台端口仅绑定 loopback，可通过 SSH 隧道访问。

镜像内置 SkyWalking Java agent，设置 `TRADEPASS_TRACING_ENABLED=true`、服务名称 `SW_AGENT_NAME` 和私网 `SW_AGENT_COLLECTOR_BACKEND_SERVICES` 后启用。Kubernetes 中通过 chart runtime 配置启用，同一角色实例共用服务名称。

Arthas 按需诊断：将从可信制品库取得的 arthas-boot.jar 放到容器可写的 `/tmp`，设置文件路径与发布 SHA-256 后在容器内运行 `bash /app/arthas.sh`。只绑定容器 loopback，不暴露诊断端口；诊断结束后停止 Arthas。镜像使用 JDK 17 提供 attach 能力。

组件配置参考：[Seata 2.1.0](https://github.com/apache/incubator-seata/tree/v2.1.0)、[XXL-JOB 3.2.0](https://github.com/xuxueli/xxl-job/tree/3.2.0)、[SkyWalking Java agent](https://skywalking.apache.org/docs/skywalking-java/v9.5.0/en/setup/service-agent/java-agent/containerization/)、[Filebeat filestream](https://www.elastic.co/guide/en/beats/filebeat/8.17/filebeat-input-filestream.html)。

## 验证入口

按以下顺序验证，所有脚本均在仓库根目录运行：

```bash
# 隔离 MySQL、Seata、RocketMQ；完整 Java 和交付脚本回归
bash scripts/ci/verify.sh

# 新建并自动清理隔离 Nacos，复跑注册发现和动态 Sentinel 规则验证
bash scripts/ci/with-nacos.sh bash scripts/ci/verify.sh

# 上述 Maven 构建完成后，验证最终镜像及全新服务器初始化
for service in identity contract trade settlement file gateway; do
  case "$service" in
    gateway) df=tradepass-gateway/Dockerfile ;;
    *) df="tradepass-module-${service}/tradepass-module-${service}-server/Dockerfile" ;;
  esac
  docker build -f "$df" --tag "tradepass-${service}:split-validation" .
done
bash scripts/ci/verify-compose.sh
```

容器验收脚本使用独立项目名、网络、密码和临时数据卷，验证四库初始化、六个打包服务及真实 XXL-JOB 后台对恢复执行器的调度，退出时只清理该次测试创建的资源。CI 已接入这些入口。Surefire/Failsafe 报告在各 `*-server`、`deploy/integration-tests`、`deploy/smoke-tests` 的 `target/` 与 `dist/ci/`；最近实际运行结果见[验证记录](server-microservices-verification.md)。真实 OSS/微信/法大大凭据、Kubernetes 集群及生产故障演练需在目标环境验证；本地通过不能替代正式切流验收。
