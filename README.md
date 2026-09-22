# TradePass-new · sqt-backend

迁移后的独立微服务工程，Java 17 / Spring Boot / Spring Cloud Alibaba。根目录是统一 Maven 父工程，各服务和公共模块直接位于根目录。

## 模块

| 模块 | 用途 |
|---|---|
| `tradepass-module-*`（api + server） | 业务域；包分层对齐 yudao：`api`/`enums` + server 侧 `controller`/`service`/`dal`/`convert`/`framework`/`job`/`util`，启动类 `*ServerApplication` |
| `tradepass-gateway` | 统一 API 入口、路由与限流 |
| `tradepass-framework/tradepass-common` / `tradepass-framework/tradepass-spring-boot-starter-governance` / `tradepass-framework/tradepass-spring-boot-starter-service` | 公共能力、服务治理、各服务运行配置 |
| `tradepass-framework/tradepass-spring-boot-starter-rpc` | 跨服务接口适配和分布式事务 |
| `sql/mysql/` | 历史单体库 Flyway 脚本（V1–V36），供测试与分库迁移参考 |
| `tools/database-migrator/` | 分库离线复制工具（不在默认 Maven reactor，需 `-f` 单独构建） |
| 各 `tradepass-module-*-server/src/test/java` | 领域单元/控制器测试（对齐 yudao：测试跟模块） |
| `deploy/integration-tests` | 架构边界、HTTP 契约、MySQL 跨域流程（需 `-Dtradepass.test.mysql.url`） |
| `deploy/smoke-tests` | 六进程打包集成测试（Failsafe，需独立 services 测试库） |
| `deploy/coverage` | JaCoCo 聚合报告（`-Pcoverage verify`） |

运行服务共六个，测试模块和公共库不单独部署。四个业务库使用独立账号，跨服务事务由 Seata 协调。各领域内部保留 `api`、`model`、`domain` 分层。

## 构建与验证

```bash
mvn -B -DskipTests package
mvn -B -pl tradepass-module-contract -am -DskipTests package
python3 -m pip install -r scripts/ci/requirements.txt
bash scripts/ci/verify.sh
```

完整验证需要本机 Docker，将创建隔离 MySQL、Seata 和 RocketMQ；数据库业务回归与独立进程测试使用两个不同的测试库。前端在同级 `../sqt-front` 独立维护和测试。

## 部署

Docker 构建入口在根目录：

```bash
docker build -f tradepass-module-contract/tradepass-module-contract-server/Dockerfile -t tradepass-contract:local .
```

所有服务通过 `deploy/server/service.compose.yml` 或 Kubernetes/Helm 部署，统一入口默认网关 1110 端口。配置参见 `deploy/server/.env.example`；本地连接已有中间件可参考根目录 `.env.example`。旧单体的启动脚本、Dockerfile、私有配置和运行入口已移除。

历史 V1–V36 SQL 位于 `sql/mysql/`，Flyway 测试通过 `RepoRoot.legacyMysqlMigrations()` 指向该目录。业务测试在各 `*-server` 与 framework starter 的 `src/test/java` 中运行，无单独「回归」业务模块。

- [架构和模块边界](docs/microservice-architecture.md)
- [数据库迁移、部署和验证](docs/server-microservices-cutover.md)
- [服务器部署](deploy/server/README.md)
- [CI/CD](docs/cicd-deployment.md)
- [本次结构调整](MIGRATION.md)
