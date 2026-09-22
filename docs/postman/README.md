# TradePass 法大大 Postman 调试

导入同目录 collection 和 environment 两份 JSON，右上角选中环境。

## 最快验证个人认证

1. 填写 `fdd_app_id`、`fdd_app_secret`（自己的法大大应用凭据）、`client_user_id`、`phone`。使用专门调试账号；如需核对现有业务，client_user_id 必须与数据库 fadada_user_identity.client_user_id 一致，不要猜测关联 ID。
2. 发送“获取访问令牌”，成功后自动保存 fdd_access_token。
3. 发送“获取个人认证地址”，复制返回 authUrl 到支持该页面的浏览器/小程序打开。默认填手机号，未锁定修改。
4. 认证完成后发送“查询个人账号及授权状态”，自动保存 open_user_id；然后查询实名信息。

`callback_url` 默认空，脚本会省略此字段，便于单独调试；要联调商签通回调时填写有效公网 HTTPS 回调地址，并使用与业务数据库对应的身份/任务。直接 API 创建的独立任务不一定会被商签通识别。认证返回路径已省略，勿把 /pages/... 当作公网 URL 填入。

## 其他流程

企业认证 → 查询企业账号（保存 open_corp_id）→ 查询实名信息/印章 → 印章管理。

合同：获取上传地址 → PUT 请求 Body 选择 PDF → 文件处理（保存 file_id）→ 填双方企业、印章、主题及业务参考号 → 创建任务（保存 sign_task_id）→ 获取签署地址 → 查询状态/下载。PDF 包含“供方盖章”“需方盖章”定位词。印章 ID 使用字符串，避免 19 位数精度损失。实际业务发起方可为供方或需方，样例以供方发起。

下载/切片接口返回的临时 URL 按返回结构单独 GET，不添加本集合的法大大签名头。撤回/作废改变服务商任务状态，直接调用不会经过商签通权限、快照及状态编排；需要完整业务联调时使用第 07 文件夹。

## 商签通后端

填 tradepass_token（自己的有效会话，不加 Bearer）、company_id、contract_id。这些请求使用商签通权限校验；token 不是法大大的 accessToken。小程序平时使用 callContainer，Postman 使用公网 HTTPS 入口。没有伪造微信身份头的登录请求。回调 /api/fadada/callback 是服务商主动通知的验签入口，不是正常手动业务调用；通过真实认证/签署触发。

## 签名和验证范围

集合脚本严格按照当前 Java SDK：毫秒时间戳、32 位 nonce、X-FASC 请求头和 bizContent 按键排序，SHA256 后以两级 HMAC-SHA256 生成签名。使用 Postman 内置 crypto-js。请求体是表单 bizContent，不是裸 JSON。令牌过期重新获取。凭据及会话留空，未读取或导出真实密钥。没有代用户执行真实认证/签署。

来源：仓库 tradepass-framework/tradepass-common/src/main/java/com/tradepass/integration/fadada 下各 Gateway；官方 Maven SDK 源码 https://repo.maven.apache.org/maven2/com/fadada/api/fasc-openapi-java-sdk/5.8.7.0428.3/fasc-openapi-java-sdk-5.8.7.0428.3-sources.jar 中 OpenApiUrlConstants、OpenApiClient、RequestConstants、FddCryptUtil。

签署地址业务当前仍传内部 redirectMiniAppUrl，可能重现 URL 格式错误；直连样例省略该可选字段。此交付不修改业务代码。

## 当前实际调用清单

以下 19 项均为 POST，前缀为 fdd_base_url。额外文件上传使用动态 URL 的 PUT。

| 用途 | 路径 |
|---|---|
|获取访问令牌|`/service/get-access-token`|
|获取个人认证地址（手机号可修改）|`/user/get-auth-url`|
|查询个人账号及授权状态|`/user/get`|
|查询个人实名信息|`/user/get-identity-info`|
|获取企业认证地址|`/corp/get-auth-url`|
|查询企业账号及授权状态|`/corp/get`|
|查询企业实名信息|`/corp/get-identity-info`|
|查询企业印章|`/seal/get-seal-info-list`|
|获取印章管理地址|`/seal/manage/get-url`|
|获取上传地址|`/file/get-upload-url`|
|处理上传文件|`/file/process`|
|创建双方合同签署任务|`/sign-task/create`|
|获取参与方签署地址|`/sign-task/actor/get-url`|
|查询签署任务详情|`/sign-task/app/get-detail`|
|查询参与方签署状态|`/sign-task/actor/list`|
|获取签署文件下载地址|`/sign-task/owner/get-download-url`|
|获取签署文件切片预览地址|`/sign-task/owner/get-slicing-ticket-id`|
|撤回签署任务|`/sign-task/cancel`|
|创建合同作废任务|`/sign-task/abolish`|
