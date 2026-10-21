# 单据下载与详情超时：服务器日志核查

核查日期：2026-10-21。视频录制于 2026-10-20 20:50（北京时间），销售单 `XS-20261020-4B554446`。

## 服务器证据

本次仅通过 SSH 读取 Docker 日志、Nacos 配置、MySQL 状态和语句摘要，未修改服务器配置、业务数据或发布服务。

- Nacos 中 business 的 `spring.datasource.hikari.maximum-pool-size=3`、`minimum-idle=0`，运行环境 `SEATA_ENABLED=false`。
- 20:50:04 与 20:50:36，business 报 `CannotCreateTransactionException`，底层为 `HikariPool-1 - Connection is not available, request timed out after 30000ms`；连接状态 `total=3, active=3, idle=0`，等待者最高在采集日志中达到 20。
- 堆栈包含 `syncCurrent → sync → reconcileAbolishIntent → ContractAbolishIntentServiceImpl.pending`。同时，单据、物流、附件查询在 `ContractReadServiceImpl.selectById` 等待获取事务连接。
- 同时段数据库健康检查耗时 11,345、29,632、24,391 毫秒。
- Nginx 在 20:45–21:00 共记录 31 个 499（客户端先断开）。20:50:34.921，销售单 `/api/trade-documents/2112526413952458753/pdf-data` 返回 499、响应正文 0 字节；同一 PDF 接口在 20:48:42 曾成功返回 200、67,792 字节。
- MySQL 的 `SELECT * FROM trade_contract WHERE id = ? FOR UPDATE` 语句摘要累计耗时 124.69 秒、单次最高 30.53 秒；全局行锁等待 21 次。这些是服务启动后的累计值，不能单独定位每一次历史等待。
- MySQL `max_connections=60`、`Max_used_connections=8`、`Connection_errors_max_connections=0`。故直接耗尽的是 business 应用连接池，未见 MySQL 达到全局连接数上限的证据。

## 根因

结合异常堆栈与代码，可以还原应用连接池饥饿的触发链：签署同步持有事务连接和合同行锁；资料读取原本也使用 `SELECT FOR UPDATE`，占用其余连接等待同一合同；同步流程的 `pending(REQUIRES_NEW)` 又需要新连接，因池满等待 30 秒才失败。其间页面的 15 秒请求已超时，PDF 请求也被取消。

前端等待所有辅助接口才展示正文、重复后台刷新以及无上下文的错误提示扩大了可见影响。仅修复前端或提高超时无法消除此后端根因。

## 本地修复

1. `ContractReader.selectById` 使用普通快照查询；需要修改合同相关状态的入口显式使用已有 `selectByIdForUpdate`，保留写锁及全局事务注册。
2. 四个签署事务入口使用 `READ_COMMITTED`；`pending` 在当前事务内做普通查询，既能看到最近提交的作废创建意图，也无需额外连接或间隙锁。
3. 作废创建 `begin(REQUIRES_NEW)` 和确认事务边界保持不变，继续保证远程结果不确定时可恢复及防止重复创建。

本次未调整线上连接池或执行发布。上线后应复测同一合同下并发刷新、资料列表、交易单详情、PDF 下载和签署状态同步，并观察连接池等待、数据库行锁等待及 499。

## 验证结果

- 相关签署、作废恢复单元测试 63 项通过；合同读取与单据、附件、物流、库存、备忘录相关单元测试 40 项通过。
- 新增 `MysqlSigningReadPoolConcurrencyTest` 使用真实 MySQL、生产事务代理和最大 3 连接池，4 项通过：两个写事务占用其余连接等待合同锁时同步仍可完成；同步持锁期间 PDF 读取前置检查与备忘录读取可完成；先读后等待锁仍可见新提交的意图；独立创建意图不受空查询间隙锁阻塞且在外层回滚后保留。
- 新测试从受保护的本地测试 JDBC 地址派生独立临时测试库，完成后清理本例数据和自己创建的库，不初始化共享 workflow 库。
- 在全新隔离库运行既有 `MysqlWorkflowConcurrencyTest`，22/23 项通过；唯一失败为既有 `noBusinessTableRequiresDatabaseGeneratedIds`，V39 新建的 `membership_policy_history`、`membership_signing_usage` 仍有自增列。本次未修改历史迁移，也未将该项记为通过。
- `git diff --check` 通过。测试日志保存在 `/private/tmp/tradepass-contract-read-lock-tests-20261021.log`、`/private/tmp/tradepass-abolish-pending-rc-tests-20261021.log`、`/private/tmp/tradepass-abolish-recovery-rc-tests-20261021.log`、`/private/tmp/tradepass-workflow-pool-isolated-mysql-20261021.log`。
