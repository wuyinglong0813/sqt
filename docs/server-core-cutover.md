# 三进程部署：Nacos 配置中心 + RocketMQ

应用为 gateway（1110）、identity（1111）、business（1112）。business 装配合同、交易、结算、文件模块；统一使用 `_business` 数据库和 Spring 本地事务。identity 使用 `_identity` 数据库。Seata、ELK、SkyWalking、XXL-JOB 不启用。

## 配置只维护在 Nacos

应用运行时不再使用 `.env.core`，也不再通过 Docker 环境变量传入数据库、微信、法大大、Redis、RocketMQ 和对象存储配置。Docker 只管理镜像、端口、资源限制、日志路径和启动配置文件挂载。修改应用配置时在 Nacos 控制台编辑 YAML，发布后重启相应服务；当前关闭热更新，数据库和密钥不会在请求进行中切换。

默认 namespace 为 public（空 namespace ID），Group 为 `TRADEPASS_CORE`：

| Data ID | 内容 |
| --- | --- |
| `tradepass-common.yaml` | 微信、法大大、Redis、内部调用密钥、datacenter ID |
| `tradepass-identity.yaml` | identity 数据源、连接池和内部服务发现设置 |
| `tradepass-business.yaml` | business 数据源、RocketMQ、存储及内部服务发现设置 |
| `tradepass-gateway.yaml` | `lb://tradepass-identity` / `lb://tradepass-business` 网关路由目标 |

使用原生 Spring 配置键，例如 `wechat.app-secret`、`tradepass.fadada.app-secret`、`spring.datasource.password`；不要把 `.env` 的 `KEY=value` 内容直接贴成 Nacos YAML。数据库密码分别放在所属服务，不在 common 共用同一数据库账号。

本地唯一的应用引导文件是 `.runtime/nacos/bootstrap.yml`，仅含 Nacos 地址、namespace、group、账号及配置导入声明。模板见 [bootstrap.example.yml](../deploy/server/nacos/bootstrap.example.yml)。应用要先连接 Nacos 才能取配置，所以 Nacos 的连接信息不能只存在 Nacos 内部。

外部引导文件通过 `spring.config.additional-location` 加载，优先于 JAR 内置配置，并导入 common 和当前服务的配置。原理见 [Spring Boot 外部配置文档](https://docs.spring.io/spring-boot/3.3/reference/features/external-config.html)。应用 Compose 不再注入同名业务配置，避免覆盖 Nacos。

## 已部署服务器：一次性导入现有值

在服务器仓库根目录执行。需要 Docker Compose 和 `scripts/ci/requirements.txt` 中的 PyYAML；若提示缺少 yaml，先执行 `python3 -m pip install -r scripts/ci/requirements.txt`。

```bash
python3 scripts/server/configure-core-nacos.py --publish
```

工具只在这次导入时读取现有 `deploy/server/.env.core`，由 Docker Compose 解析引号及特殊字符。使用现有 Nacos 服务和配置组：保留 Nacos 当前已有的值，仅补齐缺失项；发布前校验数据库归属、凭据和内部密钥，已有配置使用 CAS 防止覆盖并发修改，发布后回读验证。不会打印密码、创建旧配置备份或操作容器。若中途失败，已发布的 Data ID 会保留，修复后可重新运行；全部成功前不生成新的启动文件。

成功后生成以下私有文件（权限 600，Git 忽略）：

- `.runtime/nacos/bootstrap.yml`：Nacos 连接配置。
- `.runtime/core.compose.yml`：应用启动文件，业务参数来自 Nacos。
- `.runtime/infra.compose.yml`：MySQL、Redis、MQ、Nacos 自身的启动参数。
- `.runtime/edge.compose.yml`：Nginx 和证书目录。

后两项不能由尚未启动的 Nacos 提供。MySQL 初始化账号、Redis 服务端密码、Nacos 鉴权参数属于基础设施启动参数，转换后直接写入私有启动 YAML。Nacos 中的数据库/Redis 客户端凭据必须与实际服务端账号保持一致；编辑 Nacos 不会自动修改数据库用户密码。

确认脚本全部成功后，在 `deploy/server` 执行：

```bash
docker compose -f .runtime/core.compose.yml up -d --no-deps --force-recreate --wait --wait-timeout 360 identity &&
docker compose -f .runtime/core.compose.yml up -d --no-deps --force-recreate --wait --wait-timeout 360 business &&
docker compose -f .runtime/core.compose.yml up -d --no-deps --force-recreate --wait --wait-timeout 180 gateway
```

现有基础设施无需因本次应用配置切换而重建。以后启动基础设施或 Nginx：

```bash
docker compose -f .runtime/infra.compose.yml up -d --wait --wait-timeout 360
docker compose -f .runtime/edge.compose.yml up -d
```

这些命令不需要 `--env-file`。生成的文件保留原 Compose 项目名、镜像标签、卷名和绝对挂载路径。之后不再运行旧的 `configure-core-integrations.py`；微信、法大大等配置统一在 Nacos 修改。`.env.core` 不再被运行文件引用，可在切换验证完成后删除；不要删除 `.runtime`。

## 新镜像与对象存储

微信、法大大、数据源等现有 Spring 配置支持上述引导方式。OSS 与历史 COS 凭据的读取已从系统环境变量改为 Spring 属性；启用这部分功能时需要部署本次更新后的 business 镜像，否则旧镜像不会读取 Nacos 中的存储密钥。

在构建机执行，避免在 4G 业务服务器并发构建：

```bash
mvn -B -pl tradepass-business -am -DskipTests package
docker build -f tradepass-business/Dockerfile -t tradepass-business:local .
```

OSS 凭据位于 business 的 `tradepass.storage.oss.access-key-id/access-key-secret/session-token`；历史 COS 凭据对应 `legacy-cos-secret-id/legacy-cos-secret-key/legacy-cos-session-token`。CloudBase COS 适配器仍依赖云托管临时凭据服务，把 provider 改成 cloudbase-cos 并不能使其在普通服务器上工作。现有存储关闭状态不会被导入工具自动开启。

## 日常修改与验证

在 Nacos 编辑后执行以下命令即可重新读取配置，无需修改 Compose 或重新打包 Java。common 中的共享配置变化要重启所有引用它的服务；只修改某服务配置时可仅重启该服务：

```bash
docker compose -f .runtime/core.compose.yml restart identity business gateway
curl -i --max-time 10 http://127.0.0.1:11112/actuator/health/readiness
curl -i --max-time 10 https://sqt.org.cn/tcb_probe
curl -i --max-time 10 https://sqt.org.cn/api/me
```

预期依次为 200/UP、200、未登录 401。接着用新体验版验证微信登录、企业、合同、文件和法大大回调。健康检查不代替外部接口和数据验收。

Nacos 只绑定服务器回环地址。电脑可用 SSH 隧道访问控制台：

```bash
ssh -N -L 18848:127.0.0.1:8848 root@124.221.190.63
```

然后打开 `http://127.0.0.1:18848/nacos`。仍使用当前 Nacos 账号，密码只保存在服务器私有文件和 Nacos 中，不提交 Git。

## 4G 内存与 NameServer OOM

应用及常驻基础容器上限合计 3584 MiB，含 Nginx 为 3648 MiB；相对额定 4 GiB 剩余 448 MiB。以 `free -m` 实际总内存为准：系统显示约 3.6 GiB 时不能允许所有容器同时顶满上限。低并发测试文件处理、PDF、签署和 MQ 积压；持续内存不足时需扩容或移出中间件。

NameServer 上限已从 160 调到 256 MiB，Java 堆保持 64 MiB并限制代码缓存与 JVM 可见 CPU 数。若 business 出现 `callbackConsumer`、`getTopicRouteInfoFromNameServer`、`connect to null failed`，先检查 NameServer 状态，不能仅凭此异常判断 OOM：

```bash
docker inspect --format 'status={{.State.Status}} oom={{.State.OOMKilled}} restarts={{.RestartCount}}' tradepass-infra-static-rocketmq-namesrv-1
docker logs --tail 60 tradepass-infra-static-rocketmq-namesrv-1
docker stats --no-stream
free -h
```

修复启动参数后需重建对应容器，`restart` 不应用新的内存限制。应用保留 `restart: "no"`，机器重启后显式启动应用。不要删除数据卷或重新创建已有数据库。

## HTTPS 证书

Nginx 代理到 `127.0.0.1:1110`，保留 `/api` 路径，阻止公网 `/internal`、`/actuator`。TLS 目录应有 `fullchain.pem` 与 `privkey.pem`。云防火墙放行 80/443，应用和中间件端口保持回环监听。

替换证书后：

```bash
docker compose -f .runtime/edge.compose.yml exec nginx nginx -t &&
docker compose -f .runtime/edge.compose.yml exec nginx nginx -s reload
```

## 已有四库环境迁移

1. 备份数据库、原镜像版本和配置，暂停入口业务写入，停止外部任务，等待 Seata 全局事务完成（包括回滚重试、各库 `undo_log` 清空）。停止六个旧应用。保留 MySQL、Redis 和 MQ 数据卷。
旧 yudao 业务的停止命令为 `docker compose --env-file .env -f yudao.compose.yml stop`；bridge 部署则使用实际的 `service.compose.yml`。新业务使用独立 Compose 容器名，避免与旧的已停止容器冲突。

2. 使用旧 Compose 停掉 `seata`、`xxl-job-admin`；另行停止已部署的 ELK/SkyWalking。不要用 `down -v`，不要删除旧数据库。启动 core infra 时保持原 project 名和卷映射；不要使用 `--remove-orphans`。
3. 已存在的 MySQL 数据卷不会执行初始化脚本。新建空 business 库和独立账号（下面命令只创建 business，已存在时失败，不覆盖）：

```bash
docker compose -f .runtime/infra.compose.yml up -d --wait --wait-timeout 360
docker compose -f .runtime/infra.compose.yml exec -T mysql bash /docker-entrypoint-initdb.d/11-core-databases.sh --business-only
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

5. 按上文步骤准备 Nacos 配置，启动三进程。验证登录、企业切换、合同、收货库存对账、文件与法大大回调、重复消息和错误路径后再开放写入。旧业务容器与新 business 不得同时消费同一回调组。


## 验证范围

`bash scripts/ci/verify-core.sh` 是原三进程的完整业务兼容性验证入口。Nacos 原生配置的加载优先级另由 `NacosApplicationConfigTest` 检查，导入与发布逻辑由 `test_core_nacos.py` 检查。真实 Nacos 服务、外部密钥及 4G 长时间负载仍需在部署环境验收。
