# 企业成员自动同步与一次用印授权

首轮修复只做成员预检和外部办理指引，没有补上自动同步；本轮补齐这个流程。商签通已获批加入企业、具备 `contract_sign` 或 `all` 权限的成员，首次使用时自动关联到法大大的同一企业，不再把另去申请入企作为默认操作。仅角色名称是 ADMIN 不代表已取得签署权限。

企业主体已在法大大实名时，沿用现有主体办理商签通授权。已有恢复逻辑按统一社会信用代码查询，核对企业名称/信用代码、启用状态、应用授权关联与本人身份后同步；查到同名企业并不足以开通本地成员。原授权关联属于其他 clientCorpId 时仍需人工核对，不自动重绑。

签署或主动同步时，后端核对目标人的本地有效权限、实时法大大实名及真实 `accountName`。已有同一 `fddId` 成员直接复用；不存在时调用官方 `/corp/member/create` 添加普通成员，不映射远端管理员。官方规定，手机号或邮箱已存在法大大账号时自动加入企业。尚未完成首次加入的情况，可以在“企业 → 电子签成员”打开本人的 H5 激活链接，无需超管重复审批。最终签署必须核对同一 `fddId` 且状态为 `activated`。

自动添加使用官方个人实名详情返回的账号，不使用可能与法大大不同的商签通联系手机号。账号仅服务端临时使用，缺电话/邮箱时不得把返回的 `fddId` 当作电话号码。使用企业内唯一 `internalIdentifier=tradepass-user-{id}`，创建异常后只查询同一关联恢复结果，不在异常分支再次创建；后续重试先查原关联。不会覆盖其他实名身份，也不会重新创建或启用已禁用成员。

印章使用权与成员身份独立。成员管理页的“授权签署”会预填目标成员和当前配置的合同印章，让企业有用印管理权的人在小程序内的官方 H5 确认一次，可设置长期有效。已有该印章 `sealStatus=enable`、`grantStatus=effective` 的授权时直接返回 READY，不重复打开授权页。授权有效期间不需要每份合同再办理；过期或被撤回后需重新授权。公开接口没有静默后台授印方式，不能将本地 ADMIN 当作远端用印权。

授权操作者必须同时有本地成员管理及 `seal_manage` 权限，目标必须为同企业 ACTIVE 且有有效签署权限。核对的是目标员工的 `userId`，不是当前管理员或成员记录主键。官方 H5 继续验证操作者的法大大用印管理权。页面返回、旧企业 VERIFIED 或重新显示页面都不是授权证明，重复办理前需实时查询真正的授权状态。

新任务创建前核对当前合同印章的有效用印授权，缺权时不创建计费签署任务。已有签署及作废任务使用创建时冻结的印章配置，只自动关联成员，由法大大核验实际任务印章，不能拿后来更换的默认章拦截旧任务。电脑端组织编辑页保留，但不再是手机自动同步的必经页面。

成员同步已前移到入企审批、签署角色变更和个人认证成功后的后台准备，并补扫符合条件的存量成员；首次签署、本人主动同步或管理员办理授权时仍可补齐。每次同步先核对目标人的有效成员身份及签署权限，不把所有企业通讯录成员直接复制到服务商。移除本地成员或撤销签署权限后，应用会阻止继续签署；不会擅自删除独立存在的远端成员及人工授予的权限。

存量企业缺少 `organization` 授权时，企业认证页提供“完成企业签署授权”；签署中也可直接办理并保留原合同。显式补授权只以服务端实时查询的 `organizationAuthorized` 为完成证据，旧 `VERIFIED` 不会提前关闭页面。授权完成后继续原签署，已有完整范围时不重复开通。续授权保留原认证申请人、历史审批和本地成员权限；不因补充组织数据范围更换法人或新增管理员。后台补扫、迁移及发布顺序见[存量兼容修复记录](signing-experience-stock-fix-2026-10-19.md)。

依据：2026-10-19 从官方文档 JSON 接口取得当前 5.1 参数表，并与本机官方 Java SDK 5.8.7.0428.3 对照。

- [查询企业成员列表](https://cloud.fadada.com/api/edge-developer/document/nodes/TDKKCOKPHS/article?id=4QMIQEWWV9IIYOMF&version=5.1)：`organization` 授权、从 1 开始的分页、可选 `memberName`；姓名及电话不是实名证明。
- [查询企业成员详情](https://cloud.fadada.com/api/edge-developer/document/nodes/8JU17LUEC4/article?id=BLBD64EPEDB8HCMT&version=5.1)：`memberId`、可选 `fddId`，以及 `inactive/activated/disabled` 状态。
- [添加成员](https://cloud.fadada.com/api/edge-developer/document/nodes/Q9TJGLTIRJ/article?id=BZRG3LQD19SJJCXM&version=5.1)：已有手机号或邮箱账号自动加入；internalIdentifier 为企业内唯一标识。
- [个人实名信息](https://cloud.fadada.com/api/edge-developer/document/nodes/95YCVTCMWY/article?id=WSJNVUH6J6GMCT1L&version=5.1)：返回 accountName、fddId；缺联系人时 accountName 可能就是 fddId。
- [成员邀请链接](https://cloud.fadada.com/api/edge-developer/document/nodes/LXXSZSCYQZ/article?id=WHDBMXGJVQKKGBKD&version=5.1)：memberActiveEmbedUrl 支持 H5/PC。
- [印章授权给成员](https://cloud.fadada.com/api/edge-developer/document/nodes/S1T6YU4RDR/article?id=Q5LXVDSSOFEXGSDX&version=5.1)：H5/PC，由有用印管理权限的人确认；指定 memberIds 和 sealId，clientUserId 是办理管理员。
- [成员被授权印章](https://cloud.fadada.com/api/edge-developer/document/nodes/IWPZJCSBSH/article?id=2D0IU7GJPMFEHCC6&version=5.1)：查询 sealStatus、grantStatus，确认并复用有效授权。
- [获取组织管理链接](https://cloud.fadada.com/api/edge-developer/document/nodes/0MBMJ6OEK3/article?id=IBSDAPRI684O9PGW&version=5.1)：`modules=[member]`，同一 `clientUserId`，仅 PC 端，服务商核验组织管理权限。

测试使用 SDK/服务替身，覆盖已有成员复用、自动添加、本人身份不一致、未激活/禁用、稳定标识恢复、真实账号来源、权限不足、已有用印权复用和一次 H5 授权。需要发布 identity、contract/business 及小程序后，以真实企业账号验收；本地测试不代表线上已加入企业或已授予印章权限。此轮未发布或修改线上组织权限。
