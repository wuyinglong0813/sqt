# 2026-10-26 手动库存型号与签署进度修复

## 实际问题与处理

手动入库此前前后端均只有规格，没有独立型号。本次增加 `model`，型号最长 128 字、规格最长 256 字；历史数据和旧请求型号默认为空。库存唯一身份为企业、商品名、型号、规格、单位，不同型号不会合并。型号随库存选品、合同、销售单、收货、退货、零售及零售 PDF 传递，退货继承原单型号。历史“规格型号”合并列保持规格语义，乱序“产品型号 / 产品规格 / 产品名称”也分别映射。

小程序手动入库改为商品信息、入库信息分组卡片，型号与规格并排、数量与单位并排，底部固定确认区。库存列表与详情展示型号，支持型号搜索。

线上只读取证发现，两张签署相关截图可能来自不同合同：

- HT-20261024-517B188C（2113819348073455618）版本 1，本方已 `signed`、对方 `wait_sign`，任务 `sign_progress`；2026-10-26 13:59:23 的 `sign-task-signed` 回调已处理。
- HT-20261017-9839F626（2111366308405977090）版本 2，本地和法大大实时查询均为双方 `wait_sign`；旧版本任务已终止。

合同卡片和审批列表此前硬编码 PENDING 为“待签署”，忽略真实签署进度。本次按查看企业和当前版本双方 actor 状态显示“我方已签署，等待对方签署”“待我方签署”或“双方已签署，正在归档”。只改变展示，不将单方签署当作双方完成；合同仍须服务商任务结束及原归档条件满足后激活。

官方签署页面有界查询精确合同，本方签署确认后返回合同；返回页不采信通用 success 参数，短时等待回调后仍未确认则显示可重试状态。企业或登录上下文改变后忽略旧响应。

## 验证与发布记录

- 前端最终完整回归：核心 89、Node 388、法人流程 12 项通过。日志 `/private/tmp/tradepass-signing-front-review-fix-tests-20261026.log`。
- 320、375、430 宽度的库存表单、库存固定列和合同长进度徽标静态渲染验证通过。
- 后端从原生产 revision `936dfca2d18f61910c0f5c779bc28353f71e3ee6` 创建独立发布源码，排除原工作区其他身份服务未提交修改。最终 revision `562aaa5e380fde6e7638b2a8df0c5fccd3be66ef`；完整后端回归 913 项，912 通过、1 项条件跳过，0 失败/错误。日志 `/private/tmp/tradepass-inventory-signing-release-tests-20261026.log`。
- 最终发布源码的隔离 MySQL 3 项测试通过，验证业务库和独立交易库升级、历史数据保留、不同型号隔离、重复入库幂等、零售出库及退货精准匹配。日志 `/private/tmp/tradepass-inventory-model-release-mysql-tests-20261026.log`。
- 数据库迁移：全库 V48、合并业务库 V10、独立交易库 V4。
- 小程序从前端 HEAD `b7d443c51c77ba47fbe376b28a0a930e47466e4d` 独立构建，仅包含本轮修改，排除尚未发布的其他授权/隐私/成员交付修改。隔离 commit `357c975bb079428103725157a0fc31fba103905f`，核心 89、Node 368、法人流程 12 项全部通过，随后微信开发者工具正式编译、生成预览并上传开发版 `1.0.20261026` 成功。未提交微信审核或发布正式版。文件 SHA 清单 `/private/tmp/tradepass-inventory-signing-front-release-20261026-manifest.json`。

## 生产交付

后端发布版本 `562aaa5e380fde6e7638b2a8df0c5fccd3be66ef-2026102602`，镜像 `sha256:2e10dc1fa9b1cdb4e7c8d55947d9f7190024cee510ce888faf53c2ac515528a2`。仅 business 更新；identity 和 gateway 保持原镜像，三服务均 healthy。业务库 Flyway V10 成功，库存商品、业务单据商品、零售单据商品三表均确认 `model varchar(128)`。公网受保护库存接口返回预期 401，网关鉴权正常。

发布前相关三表及 Flyway 历史的 SQL 备份、原镜像和来源信息位于服务器私有目录 `/docker/tradepass/jenkins/releases/562aaa5e380fde6e7638b2a8df0c5fccd3be66ef-2026102602/backup/`。完整发布日志 `/private/tmp/tradepass-inventory-signing-production-deploy-20261026.log`。

发布后只读核对：上述两份合同仍分别为“本方已签、对方未签”和“双方未签”；没有手工更改合同状态、签名或用量。应用正常后台同步继续工作。本轮生产源码从独立 `/private/tmp` checkout 保留原提交记录合入各仓库 `main`，其他尚未提交的工作区修改保持本地。

手机预览二维码：`exports/inventory-signing-20261026/phone-preview-qr.png`。库存表单、库存型号列和签署进度截图也已复制到该目录。

预览文件位于 `/private/tmp/tradepass-inbound-preview-20261026/`、`/private/tmp/tradepass-inventory-model-preview-20261026/` 和 `/private/tmp/tradepass-contract-progress-preview-20261026/`。
