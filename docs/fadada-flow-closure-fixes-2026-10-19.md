# 法大大流程闭环修复记录（2026-10-19）

本轮针对同日只读检查报告中已确认的 9 组缺口实施修复，保留前两轮已完成的企业成员同步、模板条款与作废状态改动。本轮只修改和验证本地源码、测试及新增迁移，未部署，未修改生产账号、企业、印章、授权或合同。

## 修复范围

| 检查项 | 修复后的行为 |
| --- | --- |
| 1. 个人授权恢复、回调乱序 | 已认证状态也可主动重查；失效可重新获取授权链接；持久化事件水位，撤权优先，同时间旧成功通知不会恢复授权；撤权后 30 秒内不采纳可能尚未收敛的旧成功查询结果。已被新状态覆盖的回调标记过时并正常消费，避免无限重试。 |
| 2. 更换企业授权经办人 | 原认证申请人与本次授权操作者分开保存。已认证企业续授权不重新审批原申请、不重新授予历史申请人角色；仍核验当前操作者证据。 |
| 3. 员工首次实名与续办 | 签署、作废、成员同步先确认本人实名；认证后返回原合同或原操作。续办信息绑定登录会话和企业，切换企业、重新登录会失效。管理员给目标成员授权时，不会引导管理员代替目标实名。 |
| 4. 旧合同补用印授权 | 从服务商当前任务控件查询本方固定用章；小程序管理员选择成员后，按本合同的实际印章办理，不再静默改为当前默认章。目标成员仍须有效成员身份和签署权限。 |
| 5. H5 票据恢复 | 过期或已消费的短期链接可重新获取，刷脸续办链接使用独立接收时间，保留正在进行的会话。微信 web-view 覆盖原生组件，故在原生来源页提供可见的“重新获取链接”，正常返回不自动重开流程。 |
| 6. 签章定位 | 新任务按最终 PDF 签章单元格实际页码及中心坐标定位，转换为服务商 96 DPI 坐标。正文含“供方盖章／需方盖章”不再影响位置。已存在任务及 CONSUMED 恢复不重生成文件。 |
| 7. 上传失败与额度 | 创建请求前的校验、上传、文件处理失败使用专门异常；仅释放 RESERVED 且无任务号的预占，重复释放幂等。真正发出创建请求后结果未知仍保留 UNCERTAIN，防止重复创建或扣费。 |
| 8. 回调与主动补偿 | 完成通知到达但尚未归档／生效时保留后台重试；缺少签署证据不提前声称已归档。未收到回调也会主动查询当前合同版本；普通待签／作废中约 5 分钟、常规履约任务约 12 小时。身份约 15 分钟、未完成约 2 分钟、印章告警约 1 分钟查询，均受批量限制并有数据库并发领取。 |
| 9. 终止与过期展示 | 合同列表批量取得当前版本签署状态，返回独立 signingStatus 和展示文案，与详情一致。远端已终止／过期不能继续签署；原编辑／撤回流程保留，主业务 status 不随意改变含义。 |

发布前复核另将合同补偿任务纳入实际进程组件注册，新增真实注册器回归覆盖 contract／business／identity 三种运行角色。

另补齐退休作废子任务回调的自身任务号和原任务号校验；确认已终止的当前任务可以结束处理过时完成通知，不会永久重试。

## 原有 5.0 兼容保持

[SdkFadadaCompanyGateway](/Users/wuyinglong/IdeaProjects/wutest/TradePass-new/sqt-backend/tradepass-framework/tradepass-spring-boot-starter-fadada/src/main/java/com/tradepass/framework/fadada/core/SdkFadadaCompanyGateway.java)、[LegacyFadadaCompanyIdentityClient](/Users/wuyinglong/IdeaProjects/wutest/TradePass-new/sqt-backend/tradepass-framework/tradepass-spring-boot-starter-fadada/src/main/java/com/tradepass/framework/fadada/core/LegacyFadadaCompanyIdentityClient.java)及其独立 5.0 查询测试与只读检查时的 SHA-256 相同，本轮未修改。

继续先查询 5.1，缺少经办人信息时独立执行 5.0 只读补查，核对企业及认证版本一致后采用。没有把 5.1 SDK 中存在 getter 当作服务端必定返回有效字段的保证。

## 数据库与发布

新增可空授权操作者、事件水位、撤权时间、后台核对时间及查询索引；旧原申请人／认证历史保留。新增迁移不修改已发布的旧迁移：

- 身份独立库：`owned/identity/V3__identity_authorization_reconciliation.sql`。
- 合同独立库：`owned/contract/V5__signing_state_reconciliation.sql`。
- 合并业务库（合同／交易／结算，不包含身份表）：`db/business/V6__signing_state_reconciliation.sql`。
- 旧总库迁移镜像：`sql/mysql/V41__identity_authorization_reconciliation.sql`、`V42__signing_state_reconciliation.sql`。

身份表始终使用身份库迁移；合同表按实际运行形态使用独立合同库或合并业务库的迁移，旧总库镜像另列。发布需先让数据库及身份服务支持新字段／新内部接口，再更新合同或业务服务，最后更新小程序。本轮未执行生产迁移或发布。

## 保留的验收事项

检查报告中尚未确认的事项仍单列：法大大实际控制台订阅和域名配置、作废子任务具体用章继承、本地成员撤权与企业人工授予的远端权限如何协调、作废解除协议是否需要独立本地下载。未自动删除企业在法大大人工授予的权限，也未修改历史已签署 PDF。

## 验证结果

- 后端 `mvn -B clean test`：BUILD SUCCESS；721 条测试记录，675 通过、46 按外部资源条件跳过，无失败或错误。
- 另启用本机隔离 MySQL 专项：16 条记录，15 通过；1 项 Nacos 发布测试因未启用 Nacos 条件跳过。覆盖身份库／合同库／合并业务库的旧库升级、原申请人保留、重复启动、实际 Mapper 查询与并发领取，以及前置失败释放额度后同版本重试、未知／已确认任务不可释放。
- 前端全量：310 项通过（89 基础检查、209 Node 行为测试、12 法人身份测试）。
- CI 脚本回归：122 项通过。
- 1 页及 4 页 SALE／PURCHASE 样例已渲染并核对签章坐标，供需方位置正确。
- 数据库迁移工具已适配已审核的新迁移版本，打包成功；仍拒绝未审核版本。身份库与业务库的边界已在真实 MySQL 升级测试中确认。
- 发布前追加实际组件注册回归，确认两类补偿任务按服务角色注册。
- 两个仓库 `git diff --check` 通过；SDK 5.0 补查相关源文件哈希再次确认保持不变。

测试日志位于 `/private/tmp/tradepass-fadada-flow-backend-clean-tests.log`、`/private/tmp/tradepass-fadada-flow-mysql-tests.log`、`/private/tmp/tradepass-front-followup-tests.log`、`/private/tmp/tradepass-fadada-flow-schema-checks.log` 和 `/private/tmp/tradepass-fadada-flow-migrator-build.log`。

## 关键实现

- [个人授权恢复和排序](/Users/wuyinglong/IdeaProjects/wutest/TradePass-new/sqt-backend/tradepass-module-identity/tradepass-module-identity-server/src/main/java/com/tradepass/module/identity/service/fadada/FadadaPersonalIdentityServiceImpl.java)、[企业续授权](/Users/wuyinglong/IdeaProjects/wutest/TradePass-new/sqt-backend/tradepass-module-identity/tradepass-module-identity-server/src/main/java/com/tradepass/module/identity/service/fadada/FadadaCompanyServiceImpl.java)。
- [合同签署与实际用章授权](/Users/wuyinglong/IdeaProjects/wutest/TradePass-new/sqt-backend/tradepass-module-contract/tradepass-module-contract-server/src/main/java/com/tradepass/module/contract/service/signing/FadadaContractSigningServiceImpl.java)、[回调业务收敛](/Users/wuyinglong/IdeaProjects/wutest/TradePass-new/sqt-backend/tradepass-module-contract/tradepass-module-contract-server/src/main/java/com/tradepass/module/contract/framework/callback/FadadaCallbackProcessor.java)。
- [后台合同状态核对](/Users/wuyinglong/IdeaProjects/wutest/TradePass-new/sqt-backend/tradepass-module-contract/tradepass-module-contract-server/src/main/java/com/tradepass/module/contract/job/SigningStateReconciliationScheduler.java)、[合同列表状态展示](/Users/wuyinglong/IdeaProjects/wutest/TradePass-new/sqt-backend/tradepass-module-contract/tradepass-module-contract-server/src/main/java/com/tradepass/module/contract/service/signing/ContractSigningPresentationService.java)。

官方依据沿用检查报告中逐项核实的接口文档，包括 [企业授权](https://dev.fadada.com/api-doc/CGKNKJZFM5/77KS5R59TERABQH9/5-1)、[用印授权](https://dev.fadada.com/api-doc/S1T6YU4RDR/Q5LXVDSSOFEXGSDX/5-1)、[任务控件查询](https://dev.fadada.com/api-doc/0GCXJ9QSZM/T8T8M9Y2XC5WUPFK/5-1)和[签署任务创建](https://dev.fadada.com/api-doc/XZA5UN1MIS/NDNV8ES5LCEWUFAW/5-1)。
