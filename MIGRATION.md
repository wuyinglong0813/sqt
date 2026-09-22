# 微服务工程结构

2026-09-16：根目录改为 `tradepass-parent` Maven 父工程，各 `tradepass-*` 模块直接放置。`backend` 单体运行模块和 `services` 中间目录已移除。

**2026-09-16（yudao 放置方式）**：已删除顶层 `tradepass-regression-tests`、`tradepass-database-migrations` 等业务 reactor 模块。单元/控制器测试回迁到各 `tradepass-module-*-server` 与 `tradepass-framework/tradepass-spring-boot-starter-*`；公共夹具在 `tradepass-common` test-jar（`MybatisTestSupport`、`TestIds`、`RepoRoot`）。ArchUnit、HTTP 契约与 MySQL 跨域流程在 `deploy/integration-tests`；六进程测试在 `deploy/smoke-tests`；历史 SQL 在 `sql/mysql/`；分库工具在 `tools/database-migrator/`；覆盖率聚合在 `deploy/coverage`（profile `coverage`）。

此前版本曾将回归集中到 `tradepass-regression-tests`、六进程在 `tradepass-smoke-tests`；删除的测试只针对已移除的单体启动上下文及单体 BLOB 迁移 Runner。服务鉴权、HTTP 映射和健康指标仍由独立服务测试覆盖。

旧启动类、旧 WebConfig、单体 BLOB Runner、旧云托管环境配置、单体镜像构建、PID 启停脚本和共享库 Compose 已移除。业务规则与六个服务实现未作需求变更。

原 TradePass 目录与 TradePass-new/backups 中的备份均未修改。本目录 `.idea` 等本地 IDE 配置保持原样；IDEA 重新加载根 pom.xml 即可更新模块列表。

生产环境禁用开发入口、演示数据和体验账号特权的约束迁入共享的 `tradepass-framework/tradepass-common/application-prod.yml` 资源，原生产行为测试继续执行。

## 本次验证

- 29 个 Maven reactor 项目打包成功，六个服务 JAR 均不含单体启动类或 tradepass-server 依赖。
- Java 测试：355 项通过，19 项需要 MySQL/RocketMQ 的用例未在本轮运行；失败 0、错误 0。六进程中间件集成测试与覆盖率门槛未在本轮重跑。
- 25 项部署和发布脚本测试通过，源码边界和打包边界测试通过。
- 320 个现有微服务生产源码/资源逐文件核对一致，只有 25 个生成文件的来源路径注释更新；另外新增共享生产策略配置以保留禁用体验账号的约束。
- Docker、CI、数据库迁移工具和文档引用已同步到根目录结构。
