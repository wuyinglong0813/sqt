# 云域企业与满帅个人身份关联：生产只读取证

首轮取证完成时间：2026-10-20 15:31:31（Asia/Shanghai）。旧版资格补查会话完成时间：2026-10-20 16:08:59（Asia/Shanghai）。

## 结论

本次已通过用户授权的 SSH 会话读取生产实际配置、数据库和法大大实时响应，不再仅依据接口文档推测。

**满帅个人实名有效，云域企业实名及对商签通的授权有效；但云域当前响应无法提供可与满帅个人唯一标识直接比对的法人标识。** 5.0 的通用经办人关联办法在这条真实记录上不成立：经办人类型为 `deputy_auth`，且 `operatorId` 无有效值。不能把空值解释为武英龙的个人标识，也不能把代理人类型解释为满帅的法人证明。

旧版 API 3.0 确有法人身份证字段，但当前应用凭据的独立 OAuth 实测返回 `201001：未获取有效的平台信息`，没有取得旧版令牌。因此，当前部署凭据不能直接使用这条旧接口路径；尚不能断言法大大未保存法人资料，也不能断言厂商无法为现有应用提供免费兼容。

本次没有新增收费核验、重做认证、续授权、变更超管、修改配置或数据库。SSH 会话已经关闭。未保存或输出 SSH 密码、AppSecret、accessToken、完整个人证件号、个人 openUserId、手机号及刷脸资料。人工审核功能开发仍暂停，项目中没有保留上一轮试加的人工审核配置。

## 实时结果

实际运行应用与预期 AppId `00005270` 一致，法大大集成已启用。

| 检查项 | 实测结果 |
| --- | --- |
| 云域企业账户 | 已启用、已认证、已授权；身份、印章、签署与组织等入驻所需范围齐全 |
| 两版企业主体 | 企业名称、信用代码、法人姓名均匹配河北云域科技有限公司、正确营业执照代码及满帅 |
| 两版认证版本 | 认证方式均为 `deputy_auth`，认证成功时间一致 |
| 5.1 企业身份 | 没有返回 `operatorId/operatorType/operatorIdentMethod`；主体信息只含企业名、类型、信用代码、法人姓名 |
| 5.0 企业身份 | `operatorType=deputy_auth`；`operatorId` 无有效值，`operatorIdentMethod` 也无可用值 |
| 两版补充资料 | 仅银行开户信息，没有法人证件号或法人个人唯一标识 |
| 满帅个人账户 | 本地关联与实时个人账户一致；个人实名有效，姓名确为满帅 |
| 个人证件字段 | 返回完整身份证格式，而非脱敏格式；仅在进程内分类，未输出或保存号码 |
| 当前关联判定 | 两版企业经办人标识都无法匹配满帅的个人 `openUserId`；不能自动授予法人身份 |

官方通用关联条件仍然有效：同一 AppId 内，可信企业身份结果为 `operatorType=legal_rep`，且其 `operatorId` 等于当前已实名个人 `openUserId`。本次企业实际结果不满足这个条件。[5.0 企业身份接口](https://dev.fadada.com/api-doc/E40S6T05AV/WYBVAU3BQUQG2K9X/5-0)、[个人身份接口](https://dev.fadada.com/api-doc/95YCVTCMWY/WSJNVUH6J6GMCT1L/5-1)。

## 两个独立的入驻状态问题

- 商签通正确信用代码对应的企业记录 `2109921642259156993`，企业与法大大身份记录均为 `CANCELLED`。满帅个人记录仍为 `VERIFIED`。需要由本人继续或重新提交入驻申请，不能把已取消记录直接当作进行中的申请。
- 生产“可信应用所属企业”四项配置与实际企业账户的联合匹配结果为 false。复用云域默认数字 `clientCorpId` 前仍需核对并配置该关联。仅凭联合 false 不能断言哪一项缺失，本次未修改配置。

处理这两项只解决申请与授权关联，不会补出缺失的法人关系证明。本次 5.1 没有经办人字段，因此源码中“5.1 字段非空则不查询 5.0”的其他条件缺口不是本次记录的阻点。

## 已有产品资格的只读核查

企业已有 `billaccount_info` 授权，未新增授权。

- `/bill-account/get-usage-list` 查询企业主账户成功，返回 6 条订阅；按产品名称和编码检索，未发现四要素等独立身份校验产品。
- 应用账户查询返回 `210073`，未取得该账户的产品列表。公开错误码表中未检索到该码，未臆测其含义。
- 此结果不能替代全部合同或特殊服务资格确认，也不能保证没有未展示的能力；本次没有购买产品或执行任何收费核验。

## 可供厂商追查的请求号

| 查询 | requestId |
| --- | --- |
| 云域企业账户 | `bfb8fc80aba54cbf8c7a0ffeac013112` |
| 云域 5.1 企业身份 | `ad04a9a492754cfab3acf2aa50c019c7` |
| 云域 5.0 企业身份 | `690f82dafbdc4a4da5595e9fabc2104f` |
| 满帅个人身份 | `f0c0a33223154c078889a4c22208a880` |
| 企业主账户产品 | `c2bf9772459d42369c16c329f2a6b3de` |
| 应用账户产品 | `ea189be6cfb54886b0aeeee81ebf306e` |

## 下一步所需的厂商确认（尚未发送）

> 河北云域科技有限公司对商签通应用 00005270 的企业实名和完整授权均有效，法人满帅本人在同一应用内的个人实名也有效。实际 5.0 企业身份响应为 deputy_auth，但 operatorId 为空；5.1 没有返回经办人字段。请结合以上请求号核实：该企业代理人认证过程中是否保存了受邀法人的个人唯一身份记录，能否通过现有已授权的只读接口取得法人 openUserId 或与当前实名个人的可靠匹配结果？5.0 必填 operatorId 为空属于历史数据问题、字段授权问题还是兼容问题？请提供可用接口及验真方式。要求复用原企业主体和已有授权，保留当前超管，不新增付费产品或收费核验。若现有服务无法提供，请明确说明缺失的数据与可行的免费补证方式。

> 另查到 API 3.0 `/accounts/getCompanyInfo` 定义了 `companyInfo.legalIdCardNo`。2026-10-20 16:08 左右（北京时间），使用当前应用 00005270 和现部署 AppSecret 作为候选旧版密钥，按官方 API 3.0 协议请求 `/oauth2/accessToken`，返回 HTTP 200、code=201001、msg=未获取有效的平台信息，未取得令牌，响应无 `X-FDD-Request-Id`。请核实该应用的旧网关资格、凭据是否通用，能否在不新购服务、不新建应用、不重做认证/授权的前提下，免费通过已有企业绑定取得 companyUnionId，再查询同一企业的完整 legalIdCardNo；若不能，请提供同等效果的现有免费只读方式。请分别明确应用资格、既有授权适用性、字段是否有完整历史值、计费/用量是否为零。

在取得这项独立证据之前，不把同名、现有企业授权或代理人认证方式当作满帅本人法人身份的自动放行条件。

## 后续只读扩查：历史回调、已有印章与证书

本轮继续读取生产已保存记录与已授权印章资源，没有发起认证、创建印章、申请证书、签署任务或收费核验。

- 正确信用代码对应的本地企业身份缓存没有有效 `operator_id/operator_type`，不能补出历史个人关联。
- 找到 5 条相关 `corp-authorize` 回调，均已 `PROCESSED`。保存内容仅有授权结果、失败原因、企业业务标识、事件时间，部分有原授权企业标识；没有个人唯一身份字段。源码先验签再落库，但只保留裁剪后的 `retry_payload` 与原文 SHA-256，没有原始正文和签名头，不能根据裁剪后的 JSON 独立重验历史签名。
- 本地没有以云域这条企业记录作为发起方或相对方的电子签任务，不能从本地已签合同追查法人签名证书。
- 官方已审核印章查询 `/seal/get-seal-info-list` 按 `legal_representative_seal` 筛选，成功返回 0 条法人名章。
- `/seal/get-cert-info-list` 成功返回 2 张有效证书，全部是云域的机构证书，`ownerType=corp`，不是满帅个人证书。
- 已通过 `/seal/get-cert-file` 只读取得这两张既有证书文件，在内存用 OpenSSL 解析。两张 Subject 均对应云域企业，没有满帅姓名或其完整证件号；可见证书文本也没有该个人证件号。未申请新证书，没有保存证书文件、下载链接或个人证件值。未知扩展不能通过无结构字节匹配解释为法人关系证据。

新增只读查询请求号：

| 查询 | requestId |
| --- | --- |
| 已有法人名章列表 | `54752425a8aa4388b177dfed00728d1a` |
| 已有企业证书列表 | `3f901eb89b6c48edbf0908d5eab26d0d` |
| 第一张既有证书文件 | `0fb995116b12434e8cdf697137346aac` |
| 第二张既有证书文件 | `40adc8583b004963b558c958a726815f` |

[官方印章查询](https://dev.fadada.com/api-doc/1OA8RVXQFG/92JJXTKNMIFBBMGO/5-1)、[证书文件查询](https://dev.fadada.com/api-doc/R64AOCKVMG/3TFAQZOT0BDLBTVC/5-1)与[豸信 CA 官方业务规则](https://www.zxca.cn/caInfoLibrary)均需区分机构证书与自然人法定代表人身份。“机构法人证书”识别的是机构主体，不等于其法定代表人个人。

## 旧版候选实测：确有法人证件字段，当前凭据未通过旧网关鉴权

官方 API 3.0 的[机构实名信息查询](https://cloud.fadada.com/api/edge-developer/document/nodes/1ZZGXDJZRG/article?id=ZGAPSNAR899LSQUM) `/accounts/getCompanyInfo` 明确定义可选返回字段 `companyInfo.legalName`、`companyInfo.legalIdCardNo`，并单独返回 `hasAgent` 和认证状态。它不只给认证经办人信息，因而是代理人认证场景下值得进一步核实的候选。

再次直接读取官方完整参数表后确认：文档未规定 `hasAgent=1` 时法人证件字段必返或不返；示例使用 `hasAgent=0`，不能推广成代理人记录必有完整值。文档也未给法人证件号的单独字段权限、脱敏开关或解密接口。当前 5.1 的 [CorpIdentInfo 定义](https://dev.fadada.com/api-doc/TVYNBOQVCC/XFJK8ZJNXP3UCY3C/5-1) 只列企业名称、证件类型、企业证件号、法人姓名，没有法人身份证号输出。[企业四要素核验](https://dev.fadada.com/api-doc/DTFKHYOUHZ/CJMAYQM6COHLSKKA/5-1) 中的 `legalRepIdCertNo` 是请求输入，且该接口明确需付费购买，不能当作免费读取历史证件的途径。

同版[获取接入方信息](https://cloud.fadada.com/api/edge-developer/document/nodes/6KJNXTPARL/article?id=ESA0BJWWIDGOL7VM) `/accounts/getAccessObjectInfo` 可以返回接入企业的 `companyUnionId`，可能作为应用所属企业查机构实名信息的前一步。

已按[官方 API 3.0 SDK](https://gitee.com/fadada-tech/fadada-java-sdk-api3/blob/master/src/main/java/com/fadada/api/DefaultFadadaApiClient.java) 和其[签名实现](https://gitee.com/fadada-tech/fadada-java-sdk-api3/blob/master/src/main/java/com/fadada/api/utils/crypt/FddCryptUtil.java) 完成一次独立 OAuth 检查：

| 检查项 | 实测结果 |
| --- | --- |
| 请求 | `POST https://openapi.fadada.com/api/v3/oauth2/accessToken`，空请求体 |
| 签名 | 仅 API 3.0 的 App-Id、Grant-Type、Sign-Type、Timestamp、Nonce 五个公共字段；未传 v5 token 或 bizContent |
| 凭据来源 | 当前生产 AppId `00005270`；当前 AppSecret 仅作为旧版 AppKey 候选进行兼容性测试，没有预设两者通用 |
| HTTP / 业务结果 | HTTP `200`，code=`201001`，msg=`未获取有效的平台信息` |
| 令牌与请求号 | 没有 accessToken；响应无 `X-FDD-Request-Id`，JSON 仅含 code/data/msg |
| 当前配置 | app-id/app-secret 有配置；未发现显式 app-key、legacy-app-id、legacy-app-key 或 union-id 配置 |
| 后续业务接口 | 没有调用 getAccessObjectInfo 或 getCompanyInfo |

该结果证明当前部署凭据未被旧网关接受，不能直接落地旧版法人证件比对。它不证明该企业不存在旧版认证资料，不区分“应用未开通旧网关”与“需要另一套旧版凭据”等根因，也不证明厂商无法免费兼容。[官方返回结果规范](https://cloud.fadada.com/api/edge-developer/document/nodes/VOCMXDU0MF/article?id=JUW715FZYDQ1O9TS) 仅能确认 `2|01|001` 属于 OAuth2 模块业务错误；公开表与错误码工具没有进一步根因定义。不能仅凭文字判定未注册或密钥错误，也不能据此声称签名已经通过。

仍缺以下前置依据，故没有调用这两项旧版业务接口：

1. API 3.0 使用其自身服务地址、AppId/AppKey、OAuth 凭据和 `unionId`。没有依据把 5.1 `openCorpId` 直接当成 `unionId`，或直接复用 v5 accessToken。
2. 当前凭据 OAuth 已失败；尚未取得该应用的可用旧版资格或独立旧版凭据。旧版[实名信息授权说明](https://developer.fadada.com/portal/doc/WSJMBE9HXV) 还规定授权有效期为 180 天，不能将现有 v5 `ident_info` 授权直接视为旧版授权仍有效，或为了取得字段重新要求企业授权。
3. 旧文档没有明确免费或不扣用量的承诺；`legalIdCardNo` 也是可选字段，未保证云域历史数据必有完整值。

如果厂商确认当前应用可在既有服务和授权内免费读取同一主体的完整法人证件字段，就可以在服务端把该字段与满帅实时个人实名的完整证件字段严格比对，并检查公司、认证有效性和账号映射。这是有明确字段依据的候选，但尚未成为已验收的可用路径。

另有 SDK `/corp/get-ident-transaction-id`、`/user/get-ident-transaction-id` 返回历史 `transactionId/recordIds`；公开契约未说明记录角色、详情查询及计费边界，未调用。两边记录编号相交也不能单独证明法人角色。
