# 商签通企业入驻 Postman 逐步查看

依据 2026-10-20 当前工作区代码制作；没有替你发送线上认证、创建企业或同步请求，也没有取得一整套新的真实入驻结果。Examples 是明确标注的结构示例，点击 Send 后 Body 才是本次实际结果。当前源码与已部署版本可能不同，尤其要按真实返回判断。

## 1. 导入两个文件

- `TradePass-Company-Onboarding.postman_collection.json`
- `TradePass-Company-Onboarding.postman_environment.json`

Postman → Import → 选择文件。也可以打开任一 JSON，复制全文到 Import 的文本导入入口。导入后在右上角选中「商签通企业入驻｜填写凭据和企业资料」。

## 2. 先填这些变量

| 变量 | 填什么 |
| --- | --- |
| tradepass_base_url | 默认 `https://sqt.org.cn/api`；本地网关可改 `http://127.0.0.1:9999/api`，末尾不加斜杠 |
| tradepass_token | 本人已登录小程序的 token，原样填写，不加 `Bearer `。开发者工具 Console 可读取 `wx.getStorageSync('tradepass_token')` |
| company_name | 营业执照企业全称 |
| credit_code | 营业执照信用代码或工商注册号，按原件核对 |
| legal_person_name | 营业执照法定代表人姓名 |

只走 A 文件夹不需要法大大密钥。想看 B 原始结果，再填与部署一致的 `fdd_app_id` 和 `fdd_app_secret`；先发 B01 自动取得 token。不要把商签通登录 token 填成法大大 accessToken。

## 3. 按顺序逐条 Send

| 步骤 | 商签通接口（省略 /api） | 看什么 / 下一步 |
| --- | --- | --- |
| A01 | GET /me | 确认是本人账号，手机号已绑定；自动保存 user_id、client_user_id |
| A02 | GET /fadada/users/me/identity | `status=VERIFIED` 则跳至 A05；否则 A03 |
| A03 | POST /fadada/users/me/auth-url | 取 `data.authUrl`，由本人完成服务页；空链接检查嵌套 identity |
| A04 | POST /fadada/users/me/identity/sync | 等到 `data.status=VERIFIED` |
| A05 | POST /companies | 保存资料，自动保存 company_id；此时通常 PENDING，尚未入驻 |
| A06 | POST /me/company | 绑定当前申请人；初始 LEGAL_CANDIDATE / PENDING，尚无法人权限 |
| A07 | GET /fadada/companies/{id}/identity | 看办理前的本地记录 |
| A08 | POST /fadada/companies/{id}/auth-url | 有 url 由本人办理；无 url 看 status 分支，见下表 |
| A09 | POST /fadada/companies/{id}/identity/sync | 看 `status` 和 `failureReason`；须本地 VERIFIED 才继续 |
| A10 | GET /companies/{id} | 核对主体 `certificationStatus=VERIFIED` |
| A11 | POST /me/switch-company | 切换本次企业；检查成员 ACTIVE 和实际角色 |
| A12 | GET /me | 确认当前企业、认证和成员权限一致 |

A05 会保存企业资料，A06 会绑定申请人，A03/A08 可申请认证链接，A04/A09 会同步并保存服务商状态，A11 会切换当前企业。每次只发一条，查看结果后再继续。集合设定 Runner 执行当前一条即停止，避免跳过本人办理步骤。

认证链接由当前业务接口生成时带小程序返回路径，建议从本人手机小程序完成实名/授权，再在 Postman 查同步结果。Postman 不能替代本人刷脸。短时间重复同步可能读到约 30 秒内的缓存，不能根据相同返回判断没有调用或回调丢失。

## 4. 企业 auth-url 的几个结果

| 返回 | 含义 |
| --- | --- |
| `url` 有值 | 继续服务商认证/授权页面，完成后查 A09 |
| `url=null, status=VERIFIED` | 服务端已确认，仍查 A09/A10 再切换 |
| `url=null, status=IDENTITY_VERIFICATION_REQUIRED` | 企业已有授权，申请人本人企业身份依据不足；A09 看具体原因 |
| `url=null, status=AUTHORIZATION_REQUIRED` | 仍缺企业授权，空链接不是完成 |
| `url=null, status=IN_PROGRESS` | 仍待同步/核验，检查 A09 的 failureReason |

商签通成功封装为 `{code:0,message:"ok",data:...}`；法大大成功码为 `100000`。HTTP 200 只说明 HTTP 成功，业务码也必须看。Collection 的 Test Results 会同时检查，Visualize 会显示状态和下一步。

## 5. 查看法大大的原始结果

B 文件夹与 A 的区别：A 查询/推进商签通业务，B 直查服务商。B 结果不会自动写回商签通；A 的后台流程还会校验主体、账号关联和申请人证据。

| 步骤 | 法大大接口（POST；省略 /api/v5） | 关键结果 |
| --- | --- | --- |
| B01 | /service/get-access-token | accessToken，自动保存 |
| B02 | /user/get | openUserId、账号启用、个人实名及授权 |
| B03 | /user/get-identity-info | 本人实名详情、fddId |
| B04 | /corp/get（clientCorpId） | 本地企业业务 ID 是否对应既有授权 |
| B05 | /corp/get（corpIdentNo） | 必要时按信用代码回查；保留原 provider_client_corp_id |
| B06 | /corp/get-identity-info（5.1） | 企业名、信用代码、法人名、认证方式与时间 |
| B07 | /corp/get-identity-info（5.0） | 必要时查看原认证经办人 operatorId/operatorType |
| B08 | /corp/member/get-by-openId | 可选查本人企业成员编号 |
| B09 | /corp/member/get-detail | 可选查实际成员状态和管理角色 |
| B10 | /seal/get-seal-info-list | 已有印章，不决定本人法人身份 |

全部法大大查询自动按 SDK 的 HMAC-SHA256 算法签名；B07 同时从请求头和签名中省略版本头。请求 Body 使用 x-www-form-urlencoded 的 bizContent。令牌过期（100002）重发 B01；应用权限不足等以服务商实际错误为准。

签名脚本使用当前 Postman 内置 Web Crypto API，无需安装第三方包；Postman 官方已推荐用它替代旧 crypto-js。兼容要求及脚本能力见 [Postman 官方脚本运行库说明](https://learning.postman.com/docs/tests-and-scripts/write-scripts/postman-sandbox-reference/pm-require/)。

D 文件夹另外给出 `/user/get-auth-url` 和 `/corp/get-auth-url` 的原始授权请求模板，需要部署真实 `callback_url`，且省略可选的小程序回跳参数。正常入驻优先从 A 发起，因为独立直调不执行商签通对应的申请状态写入。D02 实际请求预填企业名、信用代码及经办人手机号，没有预填法人姓名或强制指定法人/代理人分支。

`companyAuthorizationReady` 是后端本次实时查询附带的状态，普通 GET 和部分冷却返回可能默认 false，不能仅凭它的 false 判定授权失效。应结合 `status/failureReason/organizationAuthorized` 与 B 的真实账号授权结果核对。

个人 ID 与企业 ID 不可混用：`user_id` 是商签通用户 ID；`client_user_id=tradepass-user-{user_id}`；`company_id` 是商签通企业 ID；`client_corp_id=tradepass-company-{company_id}`；`open_user_id/open_corp_id` 才是同一法大大应用返回的开放标识。企业/成员长整数保持原文，不要在表格或 JS Number 中转写。

法大大企业 `authorized/identified` 不等于本次申请人已被确认是法人。可靠的同主体、同认证版本记录中，`operatorType=legal_rep` 且 `operatorId` 严格匹配当前已实名个人 `openUserId`，才是服务端法人关联核验的关键依据；还须满足其余账号、主体及状态条件。`deputy_auth`、同名或机构证书不能代替本人法人证据。首次新企业最终角色必须实际查看，不能预先承诺自动取得 LEGAL。

## 6. 中断恢复

已有待完成企业用 C01 取回本人企业资料，手动填回对应变量后从 A06 继续，不自动选择列表第一条。绑定手机号用 C02（需手机小程序临时代码）；已有成员的法人记录同步用 C03，均不是所有用户必经步骤。已认证企业需要补充 organization 授权时仍通过 A08，办完再 A09 确认真实授权状态。

## 7. 源码依据

- sqt-front/miniprogram/pages/company-cert/company-cert.js：个人核验、保存资料、申请绑定。
- sqt-front/miniprogram/pages/fadada-auth/fadada-auth.js：授权链接与无链接分支。
- sqt-front/miniprogram/pages/service-return/service-return.js：服务端结果确认和企业切换。
- identity 模块 CompanyController、AuthController、FadadaIdentityController：业务入口。
- FadadaCompanyServiceImpl、FadadaPersonalIdentityServiceImpl：服务商调用及本地状态。
- SdkFadadaCompanyGateway、SdkFadadaUserGateway、LegacyFadadaCompanyIdentityClient：SDK 与独立 5.0 映射。
- 固定 SDK fasc-openapi-java-sdk 5.8.7.0428.3 的 FddCryptUtil：签名算法。

所有凭据和真实个人资料均留空。不要将填过个人证件、令牌或密钥的导出结果公开分享。

## 文件校验

已离线验证全部 27 个请求的预请求/响应脚本、54 项响应检查、12 次独立签名结果对比、中文和引号转义、64 位成员 ID 原样传递及环境变量自动保存。没有运行线上入驻，也没有声称 Postman 或真实服务商端到端验收完成。
