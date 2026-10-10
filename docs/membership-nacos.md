# 企业会员、名单和电子签份额配置

本版本实现企业共享会员体验、Nacos 企业黑白名单、普通活动赠额、专属份额、持久用量及小程序会员页面。购买和微信支付代码已补齐，真实商户参数仍待配置，收款默认关闭；接入见 [会员购买与支付说明](membership-payment.md)。已有基础协作功能继续免费。

## 一次发布准备

1. 在与 business 相同的 Nacos namespace 下新建 **Data ID：tradepass-membership.yaml**，三进程部署 **Group：TRADEPASS_CORE**，格式选 YAML。
2. 复制 [membership.example.yml](../deploy/server/nacos/membership.example.yml) 全文，修改日期、份额及名单，再发布。不要覆盖 common 或 business 原有配置。
3. 更新 business、gateway 镜像及小程序。business 启动时 Flyway 自动执行新迁移，网关增加会员接口。无需更改 bootstrap 导入，会员单独监听这份可选文件。

构建命令：

~~~bash
mvn -B -pl tradepass-business,tradepass-gateway -am -DskipTests package
~~~

新数据库迁移分别对应：三进程 business V4，六进程 contract V3，历史单体/隔离测试 V39。按实际部署布局运行 Flyway，不把三份相同 SQL 都手工执行在同一数据库。

首版需要更新镜像。之后修改会员策略 **只需在 Nacos 发布，无需重启**，监听推送更新并每 30 秒补充核对。多副本可能有短暂传播差异，原有数据源和密钥配置仍沿用原运维方式。

## 最小配置与北京时间

~~~yaml
tradepass:
  membership:
    trial:
      enabled: true
      activity-id: "launch-202610"
      end-at: "2026-11-08 23:59:59"
      sign-quota-per-company: 5
      total-sign-quota: 500
    billing:
      enabled: false
    blacklist: []
    whitelist: []
~~~

时间全部按 **北京时间 Asia/Shanghai** 解析，格式 **yyyy-MM-dd HH:mm:ss**，必须加引号。示例在 11 月 8 日 23:59:59 的整个最后一秒仍有效，11 月 9 日零点起结束。永久有效写 null。兼容带时区的旧 ISO 时间，日常建议统一用上述北京时间格式。

没有文件或合法空配置表示无运营赠送及名单，收款关闭，有效已购权益不删除。真实支付参数未补齐时，初次使用先配体验或白名单，否则新发起提示没有额度。配置状态无法确认且无快照时只暂停新的 CA 任务，已有任务继续。

## 白名单：无限或指定免费份额

把空白名单替换为下列列表，保留 YAML 层级：

~~~yaml
    whitelist:
      - company-id: "实际企业ID"
        company-name: "备注名称"
        mode: UNLIMITED
        valid-until: null
        reason: "长期合作"
      - company-id: "另一家实际企业ID"
        company-name: "试点企业"
        mode: QUOTA
        quota-total: 200
        valid-until: "2026-12-31 23:59:59"
        reason: "专属免费累计200份"
~~~

企业 ID 可在小程序「我的 → 会员与签署额度」复制。必须填真实 ID 并保留引号，备注名称不是匹配条件。以上中文占位符不能原样发布。

- UNLIMITED 免费无限签，不填 quota-total。仍验证实名、印章、角色权限，记录用量；上游仍消耗已购服务。
- QUOTA 设置企业专属免费累计上限，允许 0。用完不再叠加普通活动赠额，独立有效的已购额度仍可用。
- valid-until 与全站时间独立。全站体验关闭，有效白名单继续生效。
- 指定份额用完不提前结束会员有效期。

剩余 = 总额 − 专属已用 − 专属处理中预占，最小为 0。已用 30、无预占时，总额 200 剩 170，改为 300 剩 270；要再给 100 就填 130。

专属用量包括无限和定额期间的所有专属补贴签署，切模式、删后再加、改日期和重启都不清零。同一实名主体更换企业记录也不重置用量，重新建企业后由运营核对并更新 ID。

## 黑名单：暂停新发起

~~~yaml
    blacklist:
      - company-id: "实际企业ID"
        valid-until: null
        reason: "暂停新发起电子签"
~~~

黑名单优先于白名单及其他额度。解除时删此列表项，或设置有限截止日。内部 reason 不公开给企业成员。

黑名单只拦新发起 CA 合同任务；既有任务后续签署、被动接收、回调归档、基础协作和历史文件访问仍按原权限使用。不删除订单或用量。

## 活动与收款

- 延期只改 trial.end-at；提前结束设 trial.enabled 为 false，不影响独立白名单。
- 每企业赠额只在首次真正发起时分配；修改 sign-quota-per-company 不反复给老企业送额度。
- total-sign-quota 按已分配承诺控制，默认 500 能覆盖 100 家各领 5 份；白名单补贴另记，不占这 500 份。
- 同一 activity-id 改日期、关闭再开或重启不会重领。确实举行新活动才更换活动 ID。
- 活动关闭保留流水，延期恢复仍适用的剩余额度，已经预占的任务继续完成。
- billing.enabled 控制新收款，须同时配置商品、补齐支付文件并开启 payment.enabled，且客户端与企业权限满足才会开放。未补齐真实商户参数前保持 false。

## 计量和恢复

普通企业优先活动赠额再使用已购额度；定额企业先专属额度再已购额度；无限企业不扣普通赠额或已购额度。已购账户根据可信的支付结果发放，不根据配置或业务转款凭证产生付费权益。

草稿、预览、转款凭证手写确认不计 CA 份数。创建并启动真实任务成功计 1 份，同一合同版本重复打开和回调只记一次。预占及第三方回执独立持久化，页面打开失败或外层事务回滚后可以复用任务。

超时或结果不明保留 RESERVED/UNCERTAIN，不盲目重试，提示平台核对：

1. 查询 membership_signing_usage 中合同 ID、版本和参考号 contract-合同ID-v版本。
2. 已创建任务时核对服务商编号、文件编号和文档编号，完成消费及任务恢复，不能因客户端失败直接退量。
3. 只有服务商证明未创建且未扣量才能释放：受控事务中先锁 membership_policy_state 的 id=1，再锁 usage 行；核对原状态、账户预占，减少对应 membership_quota_pool.reserved_amount 1，置 usage 为 RELEASED 并保留人工核对依据。不要删除流水。之后重试重新校验额度。

一期不提供客户端人工补偿接口，避免企业自行撤销真实消耗。后续平台管理入口复用流水。

接口：

- GET /api/membership：当前企业权益、额度和策略版本。
- GET /api/membership/usage：当前企业主体最近 50 条使用记录。
- GET /api/contracts/{id}/signing/quote：本次来源和是否可继续，不扣量，创建时重新校验。

当前企业有效成员只读本企业数据，不返回其他企业名单；前端防止切换企业时旧请求覆盖新数据。

## 校验和回滚

非法日期、负数或小数份额、未知模式、字段拼错、同一名单重复企业均不生效，保留最近有效规则。开启活动必须有活动 ID 和截止时间。Nacos 断连使用数据库中的有效快照，按原截止日期正常到期。

明确删除整份文件会撤销活动和所有名单，日常结束活动用开关，解除单家用删列表项。清空名单用 []，不用空字符串。回滚配置不会回滚实际已用份额。

验收使用普通、无限、定额和黑名单企业各一例，查看会员页面及流水；对方无会员无余额仍能完成已发起合同。避免用线上账号批量签约只是为了测限额。
