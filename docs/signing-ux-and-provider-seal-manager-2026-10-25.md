# 2026-10-25 签署流程与用印权限交付

## 问题与产品目标

54.4 秒录屏显示，合同签署和组织成员授权入口重复显示商签通自定义信息处理提示，随后官方页面报“您不具备用印管理权限，无权访问”。

法人在商签通授予包含签署权限的管理员/签署角色后，应由商签通负责交付实际印章使用权。只完成成员加入不能算用印权限交付成功；目标成员也不需要因此成为服务商超管。

本次线上只读确认：光屿行法大大超管为满帅（成员 2109924173193973760）；武英龙（成员 2113812292057350144、稳定标识 tradepass-user-2109564750198476802）已激活，本人实名与账号匹配，但有效印章授权为 0。双方是不同实名身份，不能代用超管账号。姓名核对请求号 bfa91870f66f4f66a948a8b54be64c94、cffbfdc942d647feb3475f396c63f90f。

代码缺口：角色审批/调整虽会发出同步事件，后台只把成员同步到 activated 就标 READY，没有继续核对实际印章授权。成员列表也不显示交付状态。

## 已完成源码修改

- 移除自定义重复同意弹窗和页面内确认缓存。继续校验微信真实授权及官方必须完成的确认，不伪造已同意或已授权记录。
- 已确认的签署结果直接返回原合同，不再停留成功中间页。
- 角色分配后以原授权人的真实会话继续商签通内嵌官方确认，省去第二次询问。目标成员无需再给自己授权。
- 后台队列在成员同步之后继续核对当前默认签署章的有效使用权。缺授权持久化 WAITING_SEAL_AUTHORIZATION；只有实际生效才写 SIGNING_READY。历史仅成员完成的 READY 自动重新核验。
- 成员接口返回 signingStatus/signingMessage，只有真实生效映射为对外 READY。界面显示待同步、待确认或已就绪，不再将本地保存成功冒充交付完成。
- 对本次成员赋权流程，官方页面可见期间有界查询精确目标的授权状态，生效后自动返回成员列表。状态查询不创建邀请、不获取新链接、不授印；合同冻结旧章场景不使用默认章状态误判完成。
- 企业印章刷新在同一数据库事务内使旧就绪证明失效，重新核验；正在核验旧章的租约任务不能覆盖更新后的待办。
- 保持真实办理人 clientUserId 与受权人 memberId 的边界；不提升所有本地管理员为服务商超管。

## 后台直接授印的真实能力及缺口

官方 API3.0 确实支持 [POST /seal/sealAuth](https://cloud.fadada.com/api/edge-developer/document/nodes/VNS8UXVNXA/article?id=VUEFN4HKA71SY8PV)、/seal/cancelSealAuth 和 /seal/companySealDetail，可以实现后台授印、撤回和回查。此前仅据当前 5.1 接口就概括为“只能官方页面”的结论过宽。

现有 5.1 [印章授权链接](https://cloud.fadada.com/api/edge-developer/document/nodes/S1T6YU4RDR/article?id=Q5LXVDSSOFEXGSDX&version=5.1)是官方确认页面，实际登录者须有用印管理权。它可以内嵌于商签通当前权限分配流程，但不等于已实现后端直接授印。

当前准确缺口是：应用 00005270 对既有 5.1 企业、成员和印章，能否取得受支持的服务端授予/撤销用印权写接口及许可。17:02 的生产只读探测已经确认：现有凭据调用 API3 的 oauth2/accessToken 返回 HTTP 200、业务码 201001、消息“未获取有效的平台信息”，没有取得令牌。详见[生产接口兼容验证记录](fadada-server-seal-grant-compatibility-2026-10-25.md)。因此不能直接将旧版授印接口接在现有凭据后面。

若需使用 API3.0，必须核验有效 AppID/AppKey、企业租户 userToken、企业/员工 unionId 及印章资源映射，并确认云域现有份数的兼容性。API3.0 使用 X-FDD-Api-* 协议，当前 5.1 使用 X-FASC-* 与 openCorpId/memberId；没有取得可以跨版本直接混用的官方保证。不能将现有 memberId/fddId/openCorpId 猜成 unionId。

官方 [POST /accounts/getUnionIds](https://cloud.fadada.com/api/edge-developer/document/nodes/XUDGFWXJ1Y/article?id=NK7HQBJ5SIH2W28O)只能恢复旧应用已绑定 clientId 的 unionId，不能直接转换任意 5.1 ID。最新 SDK 的 EmployeeInfo.roleType 是复用响应模型，官方添加成员请求没有承诺该字段可写，不能据 setter 就生产提权。

完整研发资料：/private/tmp/fdd-server-seal-permission-research-20261025.md。

## 验证与发布状态

- 前端完整回归 465 项通过（基础 89、node:test 364、法人流程 12）；日志 /private/tmp/tradepass-signing-assignment-front-tests-20261025.log。
- 身份服务与依赖完整回归 561 项通过，0 失败/错误；日志 /private/tmp/tradepass-signing-permission-delivery-full-tests-20261025.log。
- 本地隔离 MySQL 集成 1 项通过，覆盖迁移、租约、真实成员列表 SQL、企业隔离及换章期间并发一致性；日志 /private/tmp/tradepass-signing-permission-mysql-tests-20261025.log。
- 两仓库 git diff --check 均通过。隔离 MySQL 不连接生产数据库。
- 本轮前后端源码尚未发布，未修改生产角色、授印或身份。此前云域发起配置及两笔旧预占修复保留。
- 开发者工具登录二维码已超时，未生成手机预览或上传新版本。现有 5.1 商签通内嵌办理与结果交付流程已完成源码修改；后台直接授印当前卡在 API3 应用令牌获取，仍需厂商提供现有应用可用的兼容写接口和资源映射，不能宣称已完成全自动同步。
