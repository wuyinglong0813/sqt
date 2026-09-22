# 微服务迁移验证记录

历史集成验证日期：2026-09-16（移除单体模块和扁平化目录之前）。此记录不代表新的目录结构已经重跑完整中间件集成测试；尚未创建发布提交，也未向生产环境部署或迁移真实业务数据。

## 已通过

完整入口 `bash scripts/ci/verify.sh` 成功退出：

| 验证 | 结果 |
|---|---|
| Maven reactor / Java 测试 | 393 项，失败 0、错误 0、跳过 0 |
| 小程序回归 | 22 项通过 |
| Python 交付与服务器初始化脚本 | 25 项通过 |
| 原业务覆盖率门槛 | 行覆盖率 73.811%，分支覆盖率 56.623%；原 60% / 55% 门槛未降低 |
| Prometheus 告警规则 | 通过 |
| Compose 配置解析 | 通过 |
| Helm chart | Helm 3.17.3 strict lint 和示例摘要镜像渲染通过；未安装到集群 |
| 原始文件保留 | 231 个小程序与原 V1–V36 迁移文件相对于本次续作前快照 SHA-256 一致 |

393 项包含覆盖率门槛测试。覆盖率合并原业务测试及独立服务 JVM 实际执行的数据，没有通过排除业务代码降低门槛。

### 分库和跨服务业务

`IndependentServicesIT` 的 12 项测试均通过，运行真实的六个独立 JAR、四个业务库、各库独立受限账号及 Seata 协调器：

- 原有 152 个 HTTP 映射各有唯一服务归属，原鉴权及内部接口隔离继续生效。
- 企业上下文、伪造身份拒绝、跨服务注销及身份服务不可用时的失败行为。
- 收货、库存、结算同时成功，重复提交保持原幂等行为。
- 结算数据库写入故障使交易分支回滚。
- 合同分支已经完成一阶段本地提交后，交易写入失败仍恢复合同及双边操作状态；重试可以成功。
- 文件服务失败使收货、库存和结算一起回滚。
- 审批列表中的远程名称、金额及删除过滤结果。
- 项目采购方向、金额汇总、分配与移除，以及结算名称、合同号和工作簿内容。

同一套测试还执行旧 V36 库的只读迁移计划、四库迁移及校验，检查原 ID、BLOB 字节、高精度金额和审计归属，并验证服务账号不能访问其他业务库。

### 通信和中间件

- Feign 金额反序列化保持 BigDecimal 精度；审批时间字段保留 LocalDateTime 排序语义。
- 事务适配测试覆盖原 rollback/noRollbackFor 条件、REQUIRES_NEW 独立提交和跨 HTTP 调用的 MANDATORY 本地分支。
- 真实 RocketMQ broker 接收回调事件，重复事件继续进入原认领处理器；消息不携带完整业务数据。
- 真实 XXL-JOB 执行器验证 token、注册和恢复任务调用。此项使用测试管理端，不能代替完整管理后台容器联调。
- 打包依赖边界和 Mapper 所有权检查通过；独立服务不包含其他领域的实现。

## 尚未通过本轮运行确认

| 项目 | 当前状态 |
|---|---|
| 最新分库代码的完整 Nacos 模式回归 | 已提供隔离脚本并接入 CI；本轮启动授权请求未获批准，未执行 |
| 六个最终容器与全新服务器初始化、真实 XXL-JOB 管理后台联调 | 脚本及 CI 已接入；JDK 基础镜像下载遇到 Docker Hub 连接失败，后续下载授权未获批准，未完成 |
| ELK / Filebeat / SkyWalking 全链路 | 配置已提供，Compose 静态解析和 Java agent 镜像路径已检查；整套运行未验证 |
| Kubernetes / Argo CD / HTTPS 域名与证书 | 模板已提供；未连接实际集群或域名环境 |
| 真实 OSS、旧 COS、微信和法大大服务 | 保留适配器与原接口；本轮没有使用真实云凭据联调 |
| 目标环境容量、并发可见性、协调器崩溃恢复 | 仍需上线前验收；本地跨库异常回滚通过不等于生产故障演练完成 |

业务规则、状态和金额计算未作需求变更；已有回归支持所覆盖场景的兼容性，不能据此宣称所有生产场景已经验证。Seata AT 普通读的隔离与原单库事务存在差异，详见[切换手册](server-microservices-cutover.md)。

## 复核入口与报告

- 运行命令见[切换手册的验证入口](server-microservices-cutover.md#验证入口)。
- 各领域单元/控制器测试：各 `tradepass-module-*-server/target/surefire-reports/` 及 framework starter 的 `target/surefire-reports/`。
- 架构、HTTP 契约、MySQL 跨域流程：`deploy/integration-tests/target/surefire-reports/`。
- 六进程分库测试：`deploy/smoke-tests/target/failsafe-reports/`（进程日志 `deploy/smoke-tests/target/process-logs/`）。
- MQ 与任务执行器：`tradepass-module-contract/tradepass-module-contract-server/target/surefire-reports/`。
- 事务及传输语义：`tradepass-framework/tradepass-spring-boot-starter-rpc/target/surefire-reports/`。
- 聚合覆盖率：`deploy/coverage/target/site/jacoco-aggregate/index.html`（需 `-Pcoverage`）。
- 临时基础设施日志：`dist/ci/`；最终容器联调运行后写入 `dist/ci/compose/`。

报告目录是构建产物，下次 `mvn clean` 会重建；CI 会归档测试报告。正式发布应固定代码提交与镜像摘要，并以同一版本完成尚未执行的验收。

## 前后端目录拆分

上述整体验证结果记录于目录拆分之前。拆分后，前端回归在同级 `sqt-front` 的 `npm test` 执行；本目录 `scripts/ci/verify.sh` 只负责后端及部署回归，不依赖 Node.js、npm 或前端目录。目录迁移的验证结果见 `../MIGRATION.md`。
