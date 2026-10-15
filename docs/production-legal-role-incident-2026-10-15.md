# 2026-10-15 法人本人注册后成为管理员：生产链路取证

## 确定结论

两家企业的真实成功授权回调均包含 `corpIdentProcessStatus=success`、`corpIdentMethod=legal_rep`，且本次经办人账号映射包含申请人。后端在核实该经办人是本人、且是有效企业超管后，统一返回 `ADMIN/member_super_admin`，没有把本次回调的法人认证方案转换为本地 `LEGAL`。

因此，当前管理员结果由我方角色推导分支产生，不能据此解释为用户选择了代理人认证。没有证据显示用户先取得了本地 `LEGAL` 后又被降级。

此次仅查询生产 Nginx 访问日志、认证回调、业务审核记录、成员记录和服务商只读接口；未修改生产配置、业务数据或部署。运行中的 identity、business 镜像 revision 均为 `54c5981e88958871c334b06480296897a19ea20b`。

## 对应账号与企业

- 申请人用户 ID：`2109632336449769474`，手机号仅核对掩码 `158****4330`。
- 河北特锗建筑安装工程有限公司：`2110211899793870849`。
- 河北天庆消防工程有限公司邢台市信都区分公司：`2110297586115284993`。

个人认证记录与截图一致：`VERIFIED`、`face`，通过时间为 2026-10-14 11:31:36。

## 生产时间线（北京时间）

| 时间 | 已取得的证据 |
| --- | --- |
| 10-14 11:31:36 | `user-authorize` 成功通知接收并处理完成，`success/success/face`，仅处理 1 次。 |
| 10-14 11:31:37 | 特锗公司和申请人成员记录创建。 |
| 10-14 11:31:39 | Nginx 记录首次 `POST /api/fadada/companies/2110211899793870849/auth-url`。 |
| 10-14 16:57:01 | 特锗公司再次 `POST auth-url`；当前申请开始时间也为 16:57:01。 |
| 10-14 16:58:02 | 服务商企业身份成功时间。 |
| 10-14 16:58:08 | 特锗公司 `corp-authorize` 成功通知接收。 |
| 10-14 16:58:09 | 通知处理完成，认证申请审核通过，审核原因明确记录“企业授权已确认，开通管理员身份；法人身份需单独核验”。 |
| 10-14 17:12:06 | 天庆分公司和申请人成员记录创建。 |
| 10-14 17:12:08 | Nginx 记录天庆分公司 `POST /api/fadada/companies/2110297586115284993/auth-url`，与当前申请开始时间一致。 |
| 10-14 17:14:09 | 服务商企业身份成功时间。 |
| 10-14 17:14:13 | 天庆分公司 `corp-authorize` 成功通知接收。 |
| 10-14 17:14:14 | 通知处理完成，审核通过并开通管理员身份。 |

两条企业通知均 `PROCESSED`、`attempt_count=1`、无失败原因。不是通知未到或后台一直失败。

## 两条成功回调的关键字段

```json
{
  "authResult": "success",
  "corpIdentProcessStatus": "success",
  "corpIdentMethod": "legal_rep",
  "availableStatus": "enable",
  "applicant_in_clientUserIds": true,
  "memberIdPresent": true,
  "authScope": ["ident_info", "signtask_init", "signtask_info", "signtask_file", "seal_info", "organization"]
}
```

上方两个布尔值是只读 SQL 生成的诊断结果，非原回调字段。其余值来自持久化的已验签回调白名单字段。

用于关联的事件编号：

- 特锗：`corp-authorize:02fabbeeade74424b13fe5d9c7a70408`。
- 天庆：`corp-authorize:5c94065aaf0947a5aaf32a9fb1087fc9`。

## 哪个分支决定了 ADMIN

调用链为：

`创建企业 → 建立 LEGAL_CANDIDATE/PENDING 成员 → 获取法大大企业授权链接 → 已验签 corp-authorize → syncCallback → applyAccount → resolveApplicantRole → completeProviderCertification → approve → ADMIN/ACTIVE`。

1. `AuthServiceImpl.bindCompany()` 为申请人建立 `LEGAL_CANDIDATE/PENDING`。注册填写法人姓名并不会直接授予本地法人角色。
2. `SdkFadadaCompanyGateway.createAuthUrl()` 发送企业名称/信用代码、申请人账号映射及授权范围；没有强制选择代理人，也不向服务商发送本地 `ADMIN` 角色。
3. `syncCallback()` 保留 `clientUserIds/corpIdentMethod/memberId`，没有把 `legal_rep` 丢掉。
4. `resolveApplicantRole()` 有直接法人分支，但依赖实名详情中的 `operatorId`，且要求服务商标识域确认开关。此次实时 **5.1** 详情中 `operatorId/operatorType/operatorIdentMethod` 均不存在，所以该分支无法确认法人。
5. 随后的本次经办人分支只把 `corpIdentMethod` 用于与企业详情的一致性校验；核对本人映射、成员 `activated/super_admin`、成员和个人 `fddId` 一致后，固定执行：

   ```java
   return new ApplicantRole(CertifiedApplicantRole.ADMIN, AUTHORIZATION_ADMIN, applicantClientUserId);
   ```

6. `AUTHORIZATION_ADMIN` 的值 `member_super_admin` 是我方代码生成的标记，不是服务商返回的代理人身份。它和远端 `identMethod=legal_rep` 同时落库。
7. `CompanyCertificationServiceImpl.approve()` 根据传入的 `ADMIN` 写入 `role_code=ADMIN`、`role_codes=["ADMIN"]`、`is_legal_person=0`、`is_administrator=1`、`status=ACTIVE`。两家公司当前记录和审核日志均与该路径吻合。
8. 后续查询复用已保存的 `member_super_admin`，仍映射成 `ADMIN`，因此反复刷新不会自然升级为法人。

## 代码变更来源

`git log -S AUTHORIZATION_ADMIN` 将该策略定位到 2026-10-13 19:23:55 的提交 `7a988bf`（`fix: align Fadada verification and signing with official v5.1 protocol`），生产当前 revision 包含该提交。

此前该成功经办人分支使用 `roleOf(identMethod)`；该提交增加超管及本人标识核验后，把返回值收紧为固定 `ADMIN`。其目的在于防止把既有企业的历史认证方案当作当前经办人的法人证明，但同时留下了“法人本人完成企业认证后，仍只能自动取得管理员”的流程缺口。

## 证据边界与后续判断

[法大大官方授权事件文档](https://cloud.fadada.com/api/edge-developer/document/nodes/MLDXMUT2BI/article?id=LAWS4ZE66GPJTXLA&version=5.1) 明确说明 `clientUserIds` 和 `memberId` 属于本次授权经办人，`corpIdentProcessStatus=success` 表示企业实名流程结束并通过，`corpIdentMethod=legal_rep` 是法定代表人身份认证。公开表没有明确保证每次成功授权中的实名字段一定表示本次新发生的实名，也没有把这些字段限定为历史信息。

目前已经确定为何代码写 ADMIN；关于怎样安全授予 LEGAL，应进一步区分“本次本人完成法人认证”和“已有实名企业由超管再次授权”，再完善对应证据与角色映射，不能用统一 ADMIN 作为完整的法人注册流程。

访问日志未记录服务商 H5 内部每一步操作，也没有保留当时每个服务商查询的完整响应。`operatorId` 缺失是此次实时 5.1 查询的结果；历史实际分支则由持久化的本地 `member_super_admin`、审核原因、回调事件和已部署源码共同确认。

## 无需新增四要素服务的修复证据

2026-10-15 进一步检索 [5.0 企业实名身份接口](https://dev.fadada.com/api-doc/E40S6T05AV/WYBVAU3BQUQG2K9X/5-0)，其正文明确 `operatorId` 为企业认证经办人的个人 `openUserId`，`operatorType=legal_rep` 表示企业法定代表人。固定 SDK 仅提供 5.1 客户端且硬编码子版本头，先前只核对 5.1 文档和响应，因此漏掉了这个可用途径。

对两家企业按官方 5.0 请求格式省略子版本头，实际结果均为：请求成功；三项经办人字段全部存在；`operatorType=legal_rep`；`operatorId` 与申请人的个人 `openUserId` 严格一致；企业主体、有效实名均匹配。只读查询 requestId 分别为 `9a749fc633224105b909eda7b325af1c`、`c55114b938224728bb43dee9219feb17`。

这证明当前应用可以直接取得具体本人的法人认证关联，无需仅凭姓名或历史方案推断，也无需购买和调用四要素接口。收费实现已撤回；修复改为身份查询的 5.0 只读兼容路径，并保留存量管理员的独立补核验及审核历史。实现与发布边界见[法人核验说明](legal-representative-verification.md)。尚未发布，也未更改生产角色。
