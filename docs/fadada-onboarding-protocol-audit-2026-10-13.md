# 法大大入驻协议核对（2026-10-13）

核对对象为当前 SDK `5.8.7.0428.3` 的 `v5_1` 客户端、法大大官方在线 5.1 参数表、官方刷脸中间页示例，以及用户提供的线上只读查询。在线文档通过官方 `cloud.fadada.com/api/edge-developer/document` 接口读取 `articleMarkdown`，没有把 SDK 保留的旧字段当作当前参数规范。以下源码修复需要按发布清单部署，自动化使用接口替身，不能代替真实服务商和手机验收。

**本报告的经办人权限结论已按后续全接口核对收紧，最终范围和验收记录见 [全接口核对](fadada-api-full-audit-2026-10-13.md)。**

## 核对结果与修正

| 项目 | 官方约定与发现 | 当前处理 |
| --- | --- | --- |
| 企业授权请求 | 当前参数表要求企业名称、信用代码位于 `corpIdentInfo`；旧实现发送 SDK 保留的顶层字段和未列在当前参数表中的 `corpIdentInfoMatch` | 改用 `CorpIdentInfoReq`，保留名称/信用代码不可编辑项；Postman 同步修正。不再依赖旧字段是否兼容 |
| 新企业与已实名企业 | 企业授权链接可以包含经办人注册、个人实名、企业实名及授权；已实名企业需要其超管授权，其他经办人可向超管申请 | 本产品先完成个人实名，以绑定当前平台申请人，再办理企业授权。这是本地权限策略，不是法大大强制要求先单独认证个人。不会绕过企业超管 |
| 重复授权 `exist` | 表示当前应用已存在授权；`reject` 才表示用户不允许授权 | `exist` 不再直接保存为企业实名失败。服务端重新查询授权与身份；历史原因为 `exist` 的失败记录可在主动同步时恢复待核验，不批量开通企业 |
| 原授权关联 | 回调可以返回 `existClientCorpId`、`existOpenCorpId`；这些不是当前申请人已经取得企业身份的证明 | 回调入口保留这两个官方字段。原服务商业务标识与本次记录不一致时继续拦截，并明确提示核对原授权关联，不自动替换绑定或授予权限 |
| 企业主动查询 | `/corp/get` 的信用代码、`clientCorpId`、`openCorpId` 必须三选一；`availableStatus` 是必填状态 | 标识三选一的原实现符合要求。补查 `enable`、`authorized` 及所有业务所需范围，条件不满足时不查询私有身份详情、不激活成员 |
| 个人主动查询 | `/user/get` 两种用户标识必须二选一，返回有效状态及实际授权范围；实名详情返回对应 `openUserId` | 原标识选择符合要求。SDK 映射补上 `availableStatus`；只有启用、已授权 `ident_info`、详情主体匹配且明确 `identified` 才确认成功。未确认身份不写成功时间 |
| 已有企业成功授权 | `corpIdentProcessStatus` 是可选字段；已有实名企业可以只办理授权 | 完整成功回调仍使用原快速路径；缺少可选实名进程字段时，保留已验签、本次申请时间内的当前经办人证据，再查询账号、身份及范围。未验签、过期或失败回调不作为成功经办人证明 |
| 当前经办人与法人 | `clientUserIds` 描述本次授权经办人的账号映射；`corpIdentMethod` 描述企业实名认证方案。已有企业的认证方式可能早于当前授权 | 账号映射本身既不能授予法人也不能证明管理权。新增 `organization` 授权并查询当前成员，只有有效超管和本人 `fddId` 对应才开通 `ADMIN`；SDK 经办人扩展的新自动授权默认关闭，须服务商明确确认后才可启用。不批量更改历史成员角色 |
| 手机法人核验入口 | `/corp/change-identity-info` 是企业超管变更工商信息的页面，仅支持 PC；还可能涉及企业业务标识变更 | 停止把它当手机法人刷脸入口。前端改为核对已有服务端法人证据；缺少证据时提示联系平台管理员。SDK 方法名称及错误提示明确为工商信息变更，要求已确认 `openCorpId` |
| 原生完成回跳 | `redirectMiniAppUrl` 必须编码，且不能指向 tabBar | 当前个人/企业原生结果页及编码符合要求；结果页重新查询服务端，不根据回跳参数开通权限 |
| 刷脸中间页 | 刷脸完成后回到中间页，再恢复服务商给出的回调页面；官方承载页在需要时重新创建页面 | 已按此修复隐藏页快速修改 web-view 的问题：返回可见后创建新承载页，通过一次性内存交接恢复本次回调地址，不重新申请授权 URL；重复返回不能重发已消费的跳转 |
| 服务端通知 | 表单 `bizContent`，验签后持久化并异步处理；HTTP 200 且响应体包含 `success`；通知超时为 3 秒 | 当前接口格式、ACK、HMAC、5 分钟请求时间窗及持久化重试设计符合文档。本轮只补充保存原授权标识，不把前端消息当认证证据 |
| 重试与旧通知 | 通知可重试和延迟，旧流程成功不能直接证明新的申请完成 | 新授权的开始时间在调用 SDK 前记录；快速成功路径还要确认事件属于本次申请。旧事件通过实时服务商查询核验，不能借旧通知的经办人字段新授予权限 |
| 本地已入驻搜索 | 保存企业资料、个人已实名、服务商企业主体已实名均不等于本地成员与企业已激活 | SQL 在名称/信用代码 OR 条件之外先限制 `VERIFIED`，再排序限量；前端也过滤旧接口返回的未认证记录 |

## 产品边界与尚需实际确认的部分

1. **全自动手机法人认定尚不能宣称具备完整官方依据。** 官方企业身份参数表没有说明 SDK 扩展的 `operatorId/operatorType` 标识域，示例为空。SDK 扩展的新自动授权默认关闭；只有当前有效超管成员与本人标识匹配时确认管理员，否则保留待核验。若产品要求所有法人本人在手机自行完成认定，需要法大大确认相应核验接口及当前经办人证据，不得拿历史认证方式、姓名相同或 PC 工商变更替代。
2. 已有应用授权但旧 `clientCorpId` 不属于本次记录时，状态恢复为待核验不等于绑定问题已解决。须核对真实原授权主体及账号关系；当前实现会阻止擅自重绑。旧回调中已被丢弃的原授权字段不能事后补齐，新入口只保证后续事件保留这些字段。
3. 录屏中的 `ca617f18` 来自第三方人脸页面。线上个人记录最终于 16:14:46 认证通过，16:14:50 的成功授权回调已处理。这能排除“个人始终未通过”，不能证明那个第三方提示的内部原因或确认它已消除；仍需手机复测及必要的服务商诊断。
4. 历史上仅凭回调认证方式取得的法人角色不在本次批量修改范围内，需结合实际企业、账号及身份记录单独核验。新成功授权不再产生这种法人推断。

## 官方来源

- [获取企业授权链接](https://cloud.fadada.com/api/edge-developer/document/nodes/CGKNKJZFM5/article?id=77KS5R59TERABQH9&version=5.1)
- [获取个人授权链接](https://cloud.fadada.com/api/edge-developer/document/nodes/ZS612WYE9M/article?id=IQDPQYVGZM2Y9OOC&version=5.1)
- [企业授权状态](https://cloud.fadada.com/api/edge-developer/document/nodes/X2JXRJIYRE/article?id=ELN3ZLQWR6PLXGCE&version=5.1)、[企业认证身份信息](https://cloud.fadada.com/api/edge-developer/document/nodes/DVANAYXQIW/article?id=MDVIM0NPTTRSK2YI&version=5.1)
- [个人授权状态](https://cloud.fadada.com/api/edge-developer/document/nodes/NGYBDNCTCY/article?id=HBPDIDOIS9TXV2IW&version=5.1)、[个人认证身份信息](https://cloud.fadada.com/api/edge-developer/document/nodes/95YCVTCMWY/article?id=WSJNVUH6J6GMCT1L&version=5.1)
- [授权相关事件](https://cloud.fadada.com/api/edge-developer/document/nodes/MLDXMUT2BI/article?id=LAWS4ZE66GPJTXLA&version=5.1)
- [企业工商信息变更](https://cloud.fadada.com/api/edge-developer/document/nodes/AX4GRRPRWY/article?id=F11RMDYCPI0K3S99&version=5.1)、[企业成员详情](https://cloud.fadada.com/api/edge-developer/document/nodes/8JU17LUEC4/article?id=BLBD64EPEDB8HCMT&version=5.1)
- [回调接收与响应](https://cloud.fadada.com/api/edge-developer/document/nodes/DKO6NVNYH3/article?id=UMZTMGU1FSROCUQJ&version=5.1)、[通知安全机制](https://cloud.fadada.com/api/edge-developer/document/nodes/VPELKXZN4F/article?id=CXAA1ACXCY2F5ZQU&version=5.1)
- [法大大官方刷脸中间页示例](https://gitee.com/fadada-cloud/pages-face-demo)

## 发布及验收

最终本地验证：后端 194 项通过（SDK 请求映射、个人/企业身份、企业搜索、回调入口及处理器），前端 230 项通过（89 项页面/工具回归、134 项 Node 单测、7 项法人身份页回归），共 424 项。两仓库 `git diff --check` 通过；这不包含生产发布及真实手机验收。

需同步发布 **identity、business 和小程序**，无新增数据库迁移。服务商 SDK 请求映射影响共享框架，business 的通知白名单也有修改，不能继续沿用上一轮“只更新 identity”的范围。现有线上数据只做了用户执行的只读查询，本轮没有发布或调整生产角色。

验收应覆盖新企业、已实名但未向当前应用授权的企业、已有当前应用授权的企业、超管审批、取消/拒绝、可选字段缺失、账号禁用/范围不足、旧绑定冲突、手机刷脸返回及不同经办人账号。服务端确认后才激活企业、切换企业和开通对应角色；没有当前法人证据时显示管理员身份。个人成功后继续原企业资料，未认证及误填信用代码的记录不进入已入驻搜索。
