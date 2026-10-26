# 2026-10-25 后台授印生产接口兼容验证

## 结论与范围

用户确认只有当前法大大应用 `00005270`。现有应用配置无法取得 API3 应用令牌，本次未进入企业、个人或印章授权阶段，尚未实现后台直接授印。

现有 5.1 公共接口及当前官方 Java SDK 未找到后台直接授予成员印章使用权的受支持入口；已记录的授权入口是官方 EUI。此结论仅限已核查的公共文档和 SDK，不能断言厂商没有非公开能力。

## 生产只读结果

| 项目 | 结果 |
| --- | --- |
| 来源 | 生产现有 Nacos/容器有效配置；密钥始终留在服务器内存 |
| 应用 | `00005270`，保留前导零 |
| 现有 API5 对照 | `/service/get-access-token` 成功，业务码 `100000`；请求号 `81db9b3c5c234f9c82b48d54e427ccbd`，核查时间 16:56:39 +08:00 |
| API3 核查时间 | `2026-10-25T17:02:12.771990+08:00` |
| API3 地址 | `POST https://openapi.fadada.com/api/v3/oauth2/accessToken` |
| HTTP 状态 | `200` |
| 业务码与消息 | `201001`：`未获取有效的平台信息` |
| 返回令牌 | 无 |
| 请求号 | 探测器没有取得可用的请求号 |

请求严格使用官方 Python API3 SDK 的空请求体、`client_credential` 和 HMAC-SHA256 协议；用两组合成凭据与官方 SDK 做离线签名逐字段比对。没有把 API5 的签名头、令牌或 `openCorpId/memberId` 直接作为 API3 参数使用，也没有尝试猜测授权写接口。

依据[官方返回格式](https://cloud.fadada.com/api/edge-developer/document/nodes/VOCMXDU0MF/article?id=JUW715FZYDQ1O9TS)，HTTP 200 不代表业务成功，`201001` 属于 OAuth2 授权服务的业务错误。官方错误查询未返回该码的更细说明。本次只证明 API3 应用/平台识别阶段失败，不能进一步声称原因是未购买、未开通、IP 白名单、应用不存在或签名已通过，也不能归因为武英龙个人没有用印权限。

已只读核查生产环境变量、Nacos 相关配置以及本地部署配置，没有发现独立 API3 AppID/AppKey、企业租户 `userToken` 或已有 `unionId` 映射。浏览器开发者控制台也未保持登录，无法从现有登录会话取得额外应用资料。

## API3 凭证获取与跨版本互通核查

### 凭证取得流程

[官方鉴权说明](https://cloud.fadada.com/api/edge-developer/document/nodes/SFUINCMCT9/article?id=DIXHAXNXHHC7TZV6)明确要求在开发者中心应用详情取得 AppID/AppKey，再调用 `/oauth2/accessToken` 换取应用访问令牌。不能从 API5 AppSecret 推算出 API3 AppKey，也不能把 API5 accessToken 填入 API3 请求头。

[第三方应用接入指引](https://cloud.fadada.com/api/edge-developer/document/nodes/4GJ0FTSEXD/article?id=IB7MYCGOZGPHBLZC)描述的流程是：

1. 登录 `developer.fadada.com`，完成开发者企业资格；已有资格时先检查现有应用。
2. 沙箱管理中创建沙箱应用并取得测试 AppID/AppKey，测试凭据及数据不用于生产。
3. 应用管理 → 应用列表 → 创建正式应用；应用创建后展示 AppID/AppKey，也可在详情查看。
4. 配置服务器 IP 白名单、回调地址并提交应用上线审核。
5. 后端使用相应环境的 AppID/AppKey 取得 accessToken。

该 API3 指引面向生态版第三方应用。创建/查看/配置可在后台自助执行；开发者资格与正式上线包含审核。原指南较早，当前账号实际是否有 API3 创建入口、现有应用能否直接兼容仍须登录核查，不能直接承诺需要另买套餐或可以直接自助开通。

操作独立客户企业还涉及企业租户授权：[官方租户流程](https://cloud.fadada.com/api/edge-developer/document/nodes/097AGT2YQ2/article?id=DBQ81ATLKOZF9JP7)规定，企业绑定取得 unionId 后，调用 `/thirdUser/getOpenServerUrl`，由企业管理员完成意愿授权，平台用返回的 grantCode 调用 `/thirdUser/getUserToken` 取得该企业的 userToken。应用 accessToken 与企业 userToken 是两层不同凭证。

### 数据不是全部隔离，也未证明全部互通

本轮重新在线读取原文，找到有限的正面兼容证据：

- [API5.1 文档模板列表](https://dev.fadada.com/api-doc/CB5VCWSCBS/TYXQKOBKVZRXQM85/5-1)的 `templateVersion` 明确区分 `v3`（API3.0 创建）与 `v5`（FASC 创建）。
- [API5.1 获取旧任务签署链接](https://dev.fadada.com/api-doc/SKDOH5VAEN/L2VHAAMSSHDM2RBL/5-1)能对 API3.0 已提交任务获取参与方签署链接，文档限定仅向对接过 API3.0 的客户开放，并使用个人在 5.1 授权后的 openUserId。

因此不能把两者概括为“数据完全不通”。这些证据也没有保证新建 API3 应用能自动取得现有 API5 企业、员工、印章或份数。尚需确认并回查：API3 授予的是真实对应的现有章，而 API5 对当前同一企业、员工和实际合同章也能查询到有效使用权。仅拿到 API3 token 或重新创建一枚同名章都不能完成这项验证。

## 厂商需提供的具体能力

### API5 印章接口对照（本轮在线重新读取原文）

API5 有印章管理接口。当前项目使用的 API5 文档版本为 5.1；缺口仅为“直接授予某成员使用权”的受支持写入口，不能概括成 API5 没有印章接口。

| 能力 | API5 请求路径（均为 POST） | 公开文档确认的行为 |
| --- | --- | --- |
| [创建模板印章](https://dev.fadada.com/api-doc/WXUVGTGP7P/EJGILQBNLB4DQS4W/5-1) | `/seal/create-by-template` | 后台创建印章；`unauthSuper` 仅控制是否默认授权给本企业第一个超管，不能指定任意本地管理员 |
| [印章授权给成员](https://dev.fadada.com/api-doc/S1T6YU4RDR/Q5LXVDSSOFEXGSDX/5-1) | `/seal/grant/get-url` | 返回 PC/H5 授权页面；支持带入目标成员、有效期、实际登录者的 clientUserId；访问者须有用印管理权限 |
| [解除印章授权](https://dev.fadada.com/api-doc/ZH4LNV86X7/JV3CQ8FRRBDK3IG3/5-1) | `/seal/grant/cancel` | 后台解除指定企业、成员、印章的使用权 |
| [查询成员被授权的印章](https://dev.fadada.com/api-doc/IWPZJCSBSH/2D0IU7GJPMFEHCC6/5-1) | `/seal/user/get-list` | 后台查询印章及授权生效状态，包含 `sealStatus` 和 `grantStatus` |

上述四份原文通过官方 `cloud.fadada.com/api/edge-developer/document/nodes/.../article?...&version=5.1` 重新获取成功，保存为 `/private/tmp/fdd-api51-recheck-{create,grant,cancel,membergrants}-20261025.json`。官方 SDK 5.8.7.0428.6 中相应模型为 `GetSealGrantUrlReq`、`CancelSealGrantReq`、`GetUserSealListReq`；这次交叉检查仍未找到后台直接新增成员授印的请求模型。没有调用任何生产授印或撤权接口。

优先请求为现有应用提供受支持的接口和许可，避免更换应用后破坏现有企业、成员、印章、合同或计费关系。

可转给法大大技术支持的说明：

> 应用 00005270 当前通过 API5 正常取 token，并已绑定企业和成员。业务需要由本系统的企业法人/有权授权人完成权限分配后，在后台为指定成员授予/撤销指定企业印章的使用权，并查询生效结果，不希望每次再次跳转授印 EUI。请提供适用于现有应用及既有 openCorpId/memberId/sealId 的服务端授印、撤权、回查接口，以及所需应用和企业授权范围。按官方 API3 SDK 调用正式环境 oauth2/accessToken，2026-10-25 17:02 +08:00 返回 201001“未获取有效的平台信息”、无 token。若需要另一套协议或应用，请提供受支持的企业、个人、印章资源映射方式，并确认可继续使用云域现有签署份数和原有签署流程。

这里需要的是实际成员的印章使用权，并非将全部商签通管理员提升为法大大超级管理员。[API3 后台授印接口](https://cloud.fadada.com/api/edge-developer/document/nodes/VNS8UXVNXA/article?id=VUEFN4HKA71SY8PV)本身不能证明当前 API5 应用和资源可直接调用。

## 接入后的完成标准

取得上述受支持能力后，再落实企业、成员及真实印章的核验映射，接入角色变更与撤权同步。接口 ACK 后必须回查实际结果；继续使用当前 API5 签署时，还必须在当前企业、成员、实际合同章的 API5 查询结果中确认有效使用权，才能写 `SIGNING_READY`。降权、离职、换章和写请求超时必须保留待办及回查，不能只修改本地状态。

## 操作记录

本次仅发送应用令牌请求、读取配置及公开官方资料；没有调用授印、撤权、创建签署任务、计费、变更角色或身份接口，没有修改生产 Nacos 或发布服务。现有前后端源码修复的发布状态与回归结果见[交付记录](signing-ux-and-provider-seal-manager-2026-10-25.md)。

临时诊断库：`/private/tmp/fdd_api3_token_probe_20261025.py`；协议复核：`/private/tmp/fdd-api3-token-probe-protocol-20261025.md`。生产脱敏结果：`/tmp/fdd-api3-token-probe-result-20261025.json`。这些文件不含应用密钥或访问令牌；不要输出诊断对象的完整请求头或私有字段。
