# 云域统一发起扣费：实现与上线说明

## 最终行为

启用平台发起模式后，商签通创建法大大签署任务时，`initiator` 为经核验的应用所属企业云域；`actors` 仍是合同真实供需双方。云域承担服务商用量，不加入签署方，不使用云域印章，也不修改合同正文的双方主体。

客户企业在商签通付款购买的份数继续归属该企业。创建前按现有企业账本预占 1 份，任务创建确认后消费；没有可用份数就不调用创建接口。重复请求、支付回调和签署回调沿用现有幂等控制。现有试用和运营赠额政策继续独立生效，不因本次扣费主体调整而改变。

本次不需要在每笔购买后调用虚构的“共享 N 份”接口。客户的 N 份使用权由商签通控制，实际签署服务由云域总采购量提供。法大大整体用量及现有主账户套餐是否适用于平台模式，仍以服务商账户和实际接入权限为准。

## 修改与兼容

- 创建命令将真实业务发起企业与服务商发起企业分开；签署链接、印章权限、先后顺序、合同数据权限仍对应真实企业。
- 在独立提交的额度预占记录以及本地签署任务中保存 `provider_initiator_open_corp_id`。配置变更、进程重启、外层事务回滚后，恢复任务仍查询原服务商发起企业，并严格核对真实双方及冻结文件。
- 下载、签后预览和归档使用任务中冻结的服务商发起企业。即使以后关闭平台模式或切换配置，已有平台任务仍用原身份。
- 旧记录新增列为 `NULL`，解释为原合同业务发起企业，不能批量回填云域。历史任务与未知结果预占不被切换配置改绑。
- 当前合同作废采用商签通内双方确认，不新建法大大作废任务。本次保留此规则与历史电子作废任务的同步；撤回仍按合同双方权限及原任务 ID 处理。
- 开启平台模式但应用所属企业配置不全、AppId 不匹配、企业额度服务不可用时，创建前拒绝，避免绕过企业限额或使用错误账户。

## 数据库升级

两张表均增加可空 `VARCHAR(64)` 字段 `provider_initiator_open_corp_id`：

| 数据库形态 | Flyway 迁移 |
| --- | --- |
| 线上 business 库 | `tradepass-business/src/main/resources/db/business/V9__platform_signing_initiator.sql` |
| 独立 contract 库 | `tradepass-module-contract/tradepass-module-contract-server/src/main/resources/db/owned/contract/V7__platform_signing_initiator.sql` |
| 旧共享库 | `sql/mysql/V47__platform_signing_initiator.sql` |

使用部署实际对应的一套迁移路径。默认关闭平台模式也需要此 schema，因为持久层会读取新增列。新增列不修改旧任务身份、合同状态、已用量和预占量。

## 启用配置

开关默认 `false`，保持既有部署兼容。在实际应用允许平台发起模式、并核验下列已有应用所属企业映射后，将这些字段合并到 business 使用的 Nacos 配置，不能覆盖完整配置文件：

```yaml
tradepass:
  fadada:
    platform-initiated-signing-enabled: true
    owner-enterprise-app-id: "00005270"
    owner-enterprise-open-corp-id: "a1d1ab24e3fb4529a21b7f3c03de3c62"
    owner-enterprise-credit-code: "91130402MAKJRYWN2M"
    owner-enterprise-client-corp-id: "1788597722465158940"
```

以上为此前只读核验的云域映射，不包含 AppSecret；使用其他 AppId 时必须替换成对应应用真实所属企业，不得照搬。三进程部署可使用 `TRADEPASS_CORE` 组的 `tradepass-business.yaml`，或沿现有 common 配置统一维护。配置后重启 business，使网关凭据与发起配置一致生效。环境变量路径另支持 `FADADA_PLATFORM_INITIATED_SIGNING_ENABLED`，一次性 Nacos 导入脚本按布尔值处理，默认不启用。

当前已知应用类型是企业自建应用。官方第三方应用模式一明确支持平台运营主体发起且不参与签署，但不能仅凭通用 API 字段保证当前应用已具备该商业模式的权限。此项必须核对，测试替身不能代替真实接入验收。

## 已失败合同的恢复

截图合同 `2113819348073455618` v1，以及 `2111366308405977090` v2，此前均以原业务企业发起且保留 `UNCERTAIN` 预占。新配置不会自动把它们重新发到云域名下。

上线前应重新只读核对原发起企业全部远端任务、原创建请求及扣量情况。只有证据确认未创建且未扣量时，才按受控、留存依据的流程释放对应预占，再允许同一原合同版本使用新的平台创建意图；不要删除流水或批量释放未知结果。若已找到原任务，恢复原任务，不再次创建。

## 验证与发布状态

专项验证覆盖平台与真实签署方分离、企业余额不足禁止创建、配置错误及额度服务缺失、预占和恢复身份冻结、旧 `NULL` 身份、配置关闭后的平台任务下载、真实双方归档和跨企业访问控制。隔离 MySQL 验证三种库布局从旧版升级、旧记录不改绑、Mapper 清空字段及 Flyway 重复执行。

验证结果：

- 完整 `mvn -B package` 成功：993 条测试记录，920 通过，73 按外部环境条件跳过，0 失败或错误。日志 `/private/tmp/tradepass-platform-signing-full-build-20261024.log`。
- 隔离 MySQL 专项 35 通过、1 项 Nacos 条件跳过，覆盖会员预占、三种库布局升级和旧合同可见性回归。日志 `/private/tmp/tradepass-membership-platform-initiator-integration-20261024.log`。
- Nacos 配置映射 14 项通过。日志 `/private/tmp/tradepass-platform-nacos-tests-20261024.log`。两个仓库 `git diff --check` 均通过。
- 本轮生成 `tradepass-business/target/tradepass-business-0.1.0-SNAPSHOT.jar`，SHA-256 为 `da8a30222fbef2c85b0876d9bd4562004dae0839b6300946af7dbffe6f5cf2d8`。此包由当前工作区整体构建，包含工作区已有的其他未提交变更；不能当作只包含本次修复的独立生产补丁。

当前未发布业务服务、未修改生产 Nacos、未释放线上预占，也未调用真实签署创建或扣费接口。前端沿用已移除详情页冗余用印入口的修改；本次平台扣费模式不新增前端权限。临时 MySQL 验证容器已停止。

官方依据：[第三方应用模式一](https://dev.fadada.com/api-help/SHNTKOXRAU/SPPH3SC6O4CPEFIJ)、[创建任务扣费主体](https://dev.fadada.com/api-doc/XZA5UN1MIS/NDNV8ES5LCEWUFAW/5-1)、[发起方与参与方关系](https://dev.fadada.com/api-doc/6B66Y1QPHB/HWNZ7ULFYLERDYXJ/5-1)。
