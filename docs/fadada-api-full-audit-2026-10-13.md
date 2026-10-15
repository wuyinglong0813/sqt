# 法大大全接口核对与修复记录（2026-10-13）

> 2026-10-15 补充：本文当时的法人字段缺失判断仅适用于 5.1 文档与查询。后续在官方 5.0 文档确认了 operatorId 的个人 openUserId 语义，且对本次两家生产企业的只读查询均取得匹配本人的 legal_rep 记录。已采用独立 5.0 身份查询兼容修复，无需四要素或开启原全局确认开关。详见[法人核验说明](legal-representative-verification.md)。

**结论：发现的代码偏差已修正并回归；不能据此宣称线上已修好或全自动法人流程已经获得官方确认。**

范围：仓库中当前调用的20个不同官方端点，加1个已退出移动流程的工商变更端点；另核对旧任务详情、授权/签署/印章事件、错误码、OpenId、Actor、ActorCorpMember、Field、FieldPosition、回调安全及官方刷脸示例。不是法大大所有未接入产品接口的认证报告。

依据：在线官方5.1参数表（从官方文档接口取得 articleMarkdown）与本机官方 Java SDK 5.8.7.0428.3 v5_1 源码逐项对照。公开参数规范优先于 SDK 中保留的旧字段；接口替身测试验证本地逻辑，不代替生产服务商调用或真机验收。

## 逐接口请求、响应及处理核对

| # / 接口 | 当前实际请求与限制 | 响应与权限语义 | 核对结果及本轮修正 |
| --- | --- | --- | --- |
| 1. 凭证<br>`/service/get-access-token`<br>[官方参数表](https://cloud.fadada.com/api/edge-developer/document/nodes/YIIWILVURJ/article?id=YOKJPBLGKQYSBIRV&version=5.1) | SDK HMAC 请求；AppId/时间戳/nonce；不使用普通业务 token 调用取 token。 | accessToken 非空；expiresIn 从请求开始计秒；缺省按官方7200秒。 | 修正短有效期被延长的问题；缓存扣除安全余量；并发失效只清理被拒绝的旧 token。 |
| 2. 个人授权<br>`/user/get-auth-url`<br>[官方参数表](https://cloud.fadada.com/api/edge-developer/document/nodes/ZS612WYE9M/article?id=IQDPQYVGZM2Y9OOC&version=5.1) | clientUserId=tradepass-user-{id}；accountName=当前绑定手机；authScopes=[ident_info]；callbackUrl；redirectMiniAppUrl=编码的原生结果页；不固定手机号不可编辑项。 | 完整授权 URL；不把页面回跳或 faceResult 当实名认证。 | 请求结构符合；210002 转同步，不直接标记成功；手机刷脸回跳按官方示例重建承载页。 |
| 3. 个人账号<br>`/user/get`<br>[官方参数表](https://cloud.fadada.com/api/edge-developer/document/nodes/NGYBDNCTCY/article?id=HBPDIDOIS9TXV2IW&version=5.1) | openUserId/clientUserId 二选一；初次按 clientUserId，已有账号优先 openUserId。 | 核对返回 clientUserId/openUserId、enable、authorized、ident_info；210022 可表示标识错误或账号禁用。 | 补映射 availableStatus，缺状态/范围不确认 VERIFIED；对成员身份比对额外按 clientUserId 作实时关联核验。 |
| 4. 个人实名<br>`/user/get-identity-info`<br>[官方参数表](https://cloud.fadada.com/api/edge-developer/document/nodes/95YCVTCMWY/article?id=WSJNVUH6J6GMCT1L&version=5.1) | openUserId；调用前确认 ident_info。 | 同一 openUserId 且 identified；只投影姓名、认证方式和时间；成员核验时临时使用可选 fddId，不保存手机号或证件底图。 | 补正确解析毫秒时间戳；fddId 缺失时不能证明企业成员等于本人。 |
| 5. 企业授权<br>`/corp/get-auth-url`<br>[官方参数表](https://cloud.fadada.com/api/edge-developer/document/nodes/CGKNKJZFM5/article?id=77KS5R59TERABQH9&version=5.1) | clientCorpId、clientUserId；corpIdentInfo={corpName,corpIdentNo}；corpNonEditableInfo=[corpName,corpIdentNo]；入驻请求范围 ident_info/seal_info/signtask_init/signtask_info/signtask_file/organization；callbackUrl；编码的 redirectMiniAppUrl。 | authUrl；身份与授权为不同状态；已有实名企业可能需要原超管批准。 | 修正旧顶层身份字段；新增 organization 用于当前成员权限证明；原五项继续作为已入驻签署业务所需范围。 |
| 6. 企业账号<br>`/corp/get`<br>[官方参数表](https://cloud.fadada.com/api/edge-developer/document/nodes/X2JXRJIYRE/article?id=ELN3ZLQWR6PLXGCE&version=5.1) | clientCorpId/openCorpId/corpIdentNo 三选一；210032 时允许仅按信用代码查询作为恢复线索。 | 必须 enable、authorized、业务范围齐全；返回业务标识不能属于另一条本地企业。 | 不自动重绑；exist 转待核验并查询；对未启用/未授权账号不查私有身份、不开通新权限。 |
| 7. 企业实名<br>`/corp/get-identity-info`<br>[官方参数表](https://cloud.fadada.com/api/edge-developer/document/nodes/DVANAYXQIW/article?id=MDVIM0NPTTRSK2YI&version=5.1) | openCorpId；要求 ident_info；比较企业名/信用代码/同一主体；必须 identified、法人姓名非空。 | corpIdentMethod 是企业历史认证方式；identSuccessTime 是毫秒时间戳；operatorId/operatorType 只见于 SDK 扩展和空示例，正文未定义其标识域。 | 修正时间格式；不以历史方式推断当前法人；基于 SDK 经办人 ID 的新自动授权默认关闭，服务商确认后才可启用。 |
| 8. 当前成员权限<br>`/corp/member/get-detail`<br>[官方参数表](https://cloud.fadada.com/api/edge-developer/document/nodes/8JU17LUEC4/article?id=BLBD64EPEDB8HCMT&version=5.1) | 已验签且属于当前成功授权的 memberId；openCorpId+memberId；有 organization 才调用；不使用未公开说明的 get-by-open-id 扩展。 | 同一 memberId、activated、roleType 含 super_admin、成员 fddId=实时个人实名 fddId；clientUserIds 仍须包含本地申请人。 | 新增官方成员详情路径，明确证明管理权后仅授予 ADMIN；缺少可选字段/不是超管/不是本人均待核验。 |
| 9. 印章列表<br>`/seal/get-seal-info-list`<br>[官方参数表](https://cloud.fadada.com/api/edge-developer/document/nodes/1OA8RVXQFG/article?id=92JJXTKNMIFBBMGO&version=5.1) | openCorpId；listPageNo 从1开始，listPageSize=100；读取所有分页。 | sealId/sealStatus/categoryType/entityType；primary 才属于该账号主企业；成员企业印章不可混用。 | 修正只读第一页；分页错误/重复不可作为完整快照；只有成功完整快照才进行本地印章停用及更新；未知归属不自动选用。 |
| 10. 印章管理<br>`/seal/manage/get-url`<br>[官方参数表](https://cloud.fadada.com/api/edge-developer/document/nodes/YM9XDWKKS0/article?id=OLORT7VZSPPHWGEH&version=5.1) | openCorpId+同一 clientUserId；当前调用 redirectUrl=null。 | resourceUrl 支持 PC/H5、默认10分钟；仍由法大大核验登录者印章管理权。 | 保留本地 seal_manage 权限、已认证企业和 HTTPS 检查；此接口没有 redirectMiniAppUrl，不能擅自传原生路径当 redirectUrl。 |
| 11. 工商变更（停用）<br>`/corp/change-identity-info`<br>[官方参数表](https://cloud.fadada.com/api/edge-developer/document/nodes/AX4GRRPRWY/article?id=F11RMDYCPI0K3S99&version=5.1) | 已确认 openCorpId+clientUserId；不发送新 clientCorpId。 | 只支持 PC，需企业超管；用途是工商信息变更，不是手机法人刷脸。 | 已停用手机入口；保留 SDK 方法供将来明确的工商变更用途，不在当前移动流程调用。 |
| 12. PDF 上传<br>`/file/get-upload-url`<br>[官方参数表](https://cloud.fadada.com/api/edge-developer/document/nodes/5I9NNDYFIB/article?id=STTDHQGDOUOOR8FS&version=5.1) | fileType=doc、storageType=cloud；doc 表示用途；文件≤50MB；取得 URL 后 HTTPS PUT 原 PDF，Content-Type=application/octet-stream，不把 AppSecret/token 放到存储 URL。 | uploadUrl/fddFileUrl 非空有效；上传地址有效30分钟；源文件需扩展名。 | 在配额预留和远端创建前检查大小及输入；未知上传/创建结果不盲目重复业务创建。 |
| 13. 文件处理<br>`/file/process`<br>[官方参数表](https://cloud.fadada.com/api/edge-developer/document/nodes/LGP6VPADZK/article?id=TXCWOCUQEET7PAEO&version=5.1) | storageType=cloud、separation=false；单个 fddFileUrlList，fileType=doc、fileFormat=pdf、fileName 带.pdf；≤200字符。 | fileIdList 与源文件顺序对应；当前仅一份源文件，因此取首个非空 fileId。 | 沿用正确顺序；源文件名排除禁用字符；本产品生成PDF不涉及转换多文件合并或OFD。 |
| 14. 创建签署任务<br>`/sign-task/create`<br>[官方参数表](https://cloud.fadada.com/api/edge-developer/document/nodes/XZA5UN1MIS/article?id=NDNV8ES5LCEWUFAW&version=5.1) | initiator={idType:corp,openId:发起企业}；subject≤200、transReferenceId≤128；signDocType=contract、fileFormat=pdf；autoStart/autoFinish/autoFillFinalize=true、signInOrder=true；callbackUrl；单份文档、两方签章。 | 非空 signTaskId；本地合同版本、文件 hash 与冻结快照关联；transReferenceId 是业务参考号，文档未承诺幂等。 | 保持会员预留/确认/不确定记录；预检参数提前；仅明确100002才刷新token重试，超时/泛错不重建任务。 |
| 15. 签署链接<br>`/sign-task/actor/get-url`<br>[官方参数表](https://cloud.fadada.com/api/edge-developer/document/nodes/SWX5HIG4GD/article?id=PUCMHZ1WGOVZZYHD&version=5.1) | signTaskId、actorId、同一 clientUserId；原生 redirectMiniAppUrl 编码一次；不指向 tabBar。 | 必须 actorSignTaskEmbedUrl；分发短链不能替代小程序嵌入链接；返回 URL 不随意 decode。 | 补回跳编码；本地核对合同参与企业、合同版本、签署顺序；作废使用对应子任务。 |
| 16. 任务详情<br>`/sign-task/get-detail`<br>[官方参数表](https://cloud.fadada.com/api/edge-developer/document/nodes/XBZJSMTQLL/article?id=WVEOTXLCHHEAMVDV&version=5.1) | QuerySignTaskDetailReq，调用 /sign-task/get-detail；仅查基础信息，无额外 filter。 | 返回 signTaskId 必须等于请求且状态非空；同时读取 originalSignTaskId/abolishedSignTaskId 供恢复核对。 | 从官方旧版“不推荐”的 /sign-task/app/get-detail 迁移到现行接口；不假设 SDK 能承载新增 signTaskNodes 对象。 |
| 17. 参与方状态<br>`/sign-task/actor/list`<br>[官方参数表](https://cloud.fadada.com/api/edge-developer/document/nodes/YAP9E8YZOM/article?id=TD4UUCR11B6S2BLZ&version=5.1) | signTaskId，调用 /sign-task/actor/list；这是官方公开接口，并非 SDK 私有扩展。 | actorId 唯一且非空；signStatus 精确 signed 才作为本次完成证据。 | 重复参与方拒绝；正常合同结束后双方最新签署证据齐全才下载、归档及激活；缓存的 signed 不能补齐缺失响应。 |
| 18. 签署 PDF 下载<br>`/sign-task/owner/get-download-url`<br>[官方参数表](https://cloud.fadada.com/api/edge-developer/document/nodes/CHMWR9IJQZ/article?id=GTKWBO91NQG2QVPD&version=5.1) | ownerId={idType:corp,openId:合同企业}；signTaskId、compression=false、downloadMode=download；customName 最大85。 | HTTPS下载地址；当前只有1份文档无附件，可下载直接PDF；下载并经档案检查保存后才生效。 | 超长源文件名不再发送为 customName，使用服务商默认名；只在 task_finished 后归档，sign_completed 不当成已归档。 |
| 19. 签署图像下载<br>`/sign-task/owner/get-img-download-url`<br>[官方参数表](https://cloud.fadada.com/api/edge-developer/document/nodes/ZOYL9YTSY8/article?id=UG7U9JYUPII411EB&version=5.1) | ownerId+signTaskId；文档签署结束且本地已归档后供预览。 | signTaskImgUrl 下载ZIP，按数值页序找末页PNG；应用单页8MB/最多100页、整个下载60MB为本地限制。 | 保持 HTTPS、格式检查与不可变预览缓存；这两个大小限制是本地保护，不能宣称为服务商上限。 |
| 20. 撤销未结束任务<br>`/sign-task/cancel`<br>[官方参数表](https://cloud.fadada.com/api/edge-developer/document/nodes/EFWUIBHCZ2/article?id=RF5E0HXQAQAWRCJU&version=5.1) | signTaskId、terminationNote≤250；当前平台撤回理由为固定短文本。 | 成功响应无 data，不能按普通有 data 接口判失败；任务已终止才允许本地撤回。 | 保留 invokeNoData；撤销和作废分开，已结束任务不走撤销；未知撤销结果用状态确认。 |
| 21. 作废已结束任务<br>`/sign-task/abolish`<br>[官方参数表](https://cloud.fadada.com/api/edge-developer/document/nodes/IAPPJR8JGU/article?id=3H31GNXZHHEHBOZO&version=5.1) | signTaskId；必需 abolishedInitiator={actorId:当前企业的原参与方}；docSource=platform、reason非空≤200；followOriginalConfig=true、autoStart=true、callbackUrl。 | 返回新的 abolishedSignTaskId；原签署方签完解除协议后才撤销原合同；父子关系需确认。 | 补遗漏的作废发起方；原因在创建意图前校验；既有超时不确定状态及父子任务恢复防止重复创建。 |

## 创建任务中全部嵌套参数

- 文档：docId=contract-doc、docName=PDF文件名、docFileId=处理结果；不同时传 docTemplateId；两处控件编号 supplier-seal/buyer-seal，fieldType=corp_seal、fieldName=供方盖章/需方盖章，moveable=false。
- 定位：positionMode=keyword、positionKeyword=对应盖章文本、keywordOffsetX/Y=0；followSignSize=true，使用印章自身尺寸。原42宽高已删除：官方控件单位是 px，而且 followSignSize=true 时忽略宽高。标准PDF的盖章关键词应各出现一次；多次命中会派生多个控件，按 fieldId 仅绑定首个，此项需以实际生成PDF及真签位置验收。
- 参与方：actorType=corp、actorId=supplier/buyer、actorName=企业名、actorOpenId=已授权企业标识、permissions=[sign]、sendNotification=false、sendInSiteMessage=false；产品自己提示签署。没有配置自动签、快捷个人登录或冒充用印员。
- 签署字段：fieldDocId=contract-doc、fieldId=对应角色控件、sealId=有效数字印章ID、moveable=false。fieldName 在指定 fieldId 时被忽略；当前冗余值不承担绑定作用。
- 签署配置：orderNo=1/2、requestVerifyFree=false、readingToEnd=true、resizeSeal=false。发起方先签是本地策略，不固定供方先签；BUY场景发起方可以是 buyer。
- 其他可选字段保持官方默认；没有调用提交或结束接口是因为 autoStart/autoFinish 已开启。官方 /sign-task/finish 也要求签署全部完成，不能当成跳过签署的手段。

## 通用传输、事件及状态

| 项目 | 核对与处理 |
| --- | --- |
| SDK版本及响应 | 使用v5_1客户端，子版本5.1；SDK处理请求HMAC-SHA256、AppId、毫秒时间戳、nonce及表单bizContent。成功需要SDK isSuccess，普通查询还要data非空，撤销允许无data。AppSecret不进入小程序。 |
| token/业务重试 | 100002 明确表示访问凭证失效，最多刷新后重试1次；不对100001、100003、频控、网络中断或超时自动重放创建/作废。nonce和签名由每次SDK调用重新生成。并发较新的token不会被旧失败清掉。 |
| 错误码 | 210002 是范围已重复授权，不是实名认证成功；210022/210032 也可能是ID错误或openId禁用。100004/100009/100020保留限流提示并限制查询，100020还需检查错误参数是否持续请求。 |
| 通知接收 | 表单bizContent，校验AppId、事件头、HMAC、nonce长度及±5分钟请求时间窗；持久化后异步处理；HTTP200且body包含success；不可等远端查询完成再ACK。服务商3秒超时是目标，真实部署需确认数据库/MQ时延。 |
| 通知可靠性 | 事件去重和载荷冲突保护；后台持久化重试；处理器覆盖内部_verifiedEvent，外部载荷不能自行声明验签。授权成功但资料仍不足会重试；前端postMessage和回跳不能授予权限。 |
| 授权撤销/印章变化 | 回调按主体重新查询当前权威状态；未授权/禁用后本地电子签身份不保持VERIFIED，印章完整快照停用旧印章。此操作不会无依据地删除平台已有成员或替换法人。生产事件订阅、回调地址与补偿任务仍需现场确认。 |
| 成功事件时间 | 快速路径要求已验签、本次申请时间内、对应主体/启用/实际范围；已有实名的成功授权可缺少corpIdentProcessStatus，转权威查询并保留本次成员证据；失败/旧事件不能借其成员字段新开权限。 |
| exist | 失败原因为exist只说明当前应用已有授权；恢复待核验后查账号/身份。保留existClientCorpId/existOpenCorpId作为排查线索，不凭它自动重绑或激活。reject和实名进程failed继续失败。 |
| 印章资源 | 仅完整分页成功后刷新本地资源，失败不清空；仅明确primary的印章进入新同步。旧历史印章需首次成功同步后确认归属、启用状态及实际用印权限，不能因本地记录enable就声称法大大已准许该用户盖章。 |
| 签署完成 | sign_completed和task_finished不同；只归档后者，且本次参与方查询需双方signed。作废须核对子任务/父任务，结束本地合同前走既有恢复校验；不凭前端提示或用户点按钮直接生效/作废。 |
| 缓存与权限 | 本地已实名/已入驻缓存不是服务商永久授权保证。变更回调同步、调用时服务商权限检查及恢复任务共同约束。不能将静态审核结论扩展成已验证线上持续一致性。 |

## 线上证据与尚未完成的验收

用户提供的只读查询证明：错码WN2W记录为PENDING_REVIEW/IN_PROGRESS且活跃成员0；正确WN2M记录为REJECTED/FAILED、reason=exist且活跃成员0。旧搜索未按本地认证成功过滤，因此把两条未完成入驻的资料展示成已入驻。现已在SQL分组外限制VERIFIED再排序限量，前端同时过滤旧返回。没有删除这两条资料或批量开通成员。

个人账号最终在16:14:46实名通过，16:14:50成功授权回调已处理；210022此前反复出现不能证明个人始终失败。16:28:49另一个企业收到success/legal_rep通知，只能证明该事件及本次账号映射，尚未取得该企业的当前成员详情与本地权限，不能据此宣称全部流程通过。

仍需闭环：

1. **法人自动认定。** 当前公开企业身份文档未定义operatorId/operatorType的来源及个人标识域。新SDK扩展自动授权默认关闭（tradepass.fadada.operator-open-user-id-confirmed=false），只有法大大针对本接入给出明确依据后才能开启；不通过把开关打开来掩盖流程不足。新官方成员证明仅开ADMIN，缺少法人证据需平台核验。此前靠历史认证方式产生的角色须逐企业审核，未批量变更。
2. **成员详情实际返回。** memberId、roleType、fddId可缺，且官方成员详情页面示例和响应参数结构不一致，需用真实授权后的get-detail响应验证。字段不足时待核验。存量企业补organization授权需真实超管操作，不能从exist失败回调臆造当前成员。
3. **原授权关联。** WN2M的exist及旧clientCorpId问题仍要查真实关联。新保留字段不能补回旧入口丢弃的数据，未经核验不重绑。
4. **手机刷脸与签章位置。** ca617f18是第三方页面内部提示；已修复本地承载页返回方式，但仍需原手机复测和必要的法大大请求诊断；签署、作废需实测原生回跳、盖章位置及父子任务完成事件。
5. **部署状态。** 本报告对应代码修复；Git提交和推送不代表服务器已部署。SSH不可用，本轮未直接发布或操作线上；所有线上SQL由用户执行且为只读。需要identity、business共享框架和小程序一起发布，无数据库迁移；生产环境AppId、服务地址、域名白名单、事件订阅及回调延迟仍未现场验证。

## 验证

最终本地回归：后端194项（27 SDK/通用协议、119身份/搜索、48通知/签署），前端230项（89页面及工具、134 Node、7法人页面），均通过；两仓库diff格式检查通过。测试不包含真实第三方服务调用、手机操作、发布或历史角色审核。

验证命令：

```sh
mvn -B -pl tradepass-module-identity/tradepass-module-identity-server,tradepass-module-contract/tradepass-module-contract-server -am -Dtest=CompanyServiceTest,FadadaCompanyVerificationTest,FadadaPersonalIdentityServiceTest,SdkFadadaCompanyGatewayTest,SdkFadadaUserGatewayTest,SdkFadadaSigningGatewayTest,FadadaApiSupportTest,FadadaProviderTimeTest,FadadaCallbackServiceTest,FadadaCallbackProcessorTest,FadadaSigningVersionTest,ContractSigningFlowTest,ContractSigningCancellationServiceTest,FadadaContractSigningServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

前端运行package.json中三组测试；使用Node>=18。本机使用配置的Node运行时。

## 补充官方依据

- [授权相关事件](https://cloud.fadada.com/api/edge-developer/document/nodes/MLDXMUT2BI/article?id=LAWS4ZE66GPJTXLA&version=5.1)
- [接收事件回调并响应](https://cloud.fadada.com/api/edge-developer/document/nodes/DKO6NVNYH3/article?id=UMZTMGU1FSROCUQJ&version=5.1)
- [回调通知安全机制](https://cloud.fadada.com/api/edge-developer/document/nodes/VPELKXZN4F/article?id=CXAA1ACXCY2F5ZQU&version=5.1)
- [错误码说明](https://cloud.fadada.com/api/edge-developer/document/nodes/JOQQIJXLFL/article?id=H4F0HSKMHQ3HPIIM&version=5.1)
- [OpenId](https://cloud.fadada.com/api/edge-developer/document/nodes/FVNLBYNGF4/article?id=QB2XFQCW043LLSTX&version=5.1)
- [Actor](https://cloud.fadada.com/api/edge-developer/document/nodes/BVBHTETPHF/article?id=1LFQWTVAJOEYVBAI&version=5.1)
- [ActorCorpMember](https://cloud.fadada.com/api/edge-developer/document/nodes/SN54OBQBC4/article?id=FUVXU3NA07MEHJUK&version=5.1)
- [Field](https://cloud.fadada.com/api/edge-developer/document/nodes/92ETKBJBND/article?id=2GP7HNI5MITK0QWD&version=5.1)
- [FieldPosition](https://cloud.fadada.com/api/edge-developer/document/nodes/IVVNT6NQSR/article?id=NDSRKJCJDKCE9EFQ&version=5.1)
- [签署任务相关事件](https://cloud.fadada.com/api/edge-developer/document/nodes/HZRSAZQXZR/article?id=0T8MZCFRRZTU646K&version=5.1)
- [印章相关事件](https://cloud.fadada.com/api/edge-developer/document/nodes/FKOGCIDLUP/article?id=ZQJFHIOJX1ZJFFR4&version=5.1)
- [结束签署任务](https://cloud.fadada.com/api/edge-developer/document/nodes/CHXBYHM0QO/article?id=SNJ2GFST2AA1NVQW&version=5.1)
- [查询签署任务详情（旧版不推荐）](https://cloud.fadada.com/api/edge-developer/document/nodes/TTCKCTAOLY/article?id=PTHJQQDHKTX85MGJ&version=5.1)
- [法大大官方刷脸中间页示例](https://gitee.com/fadada-cloud/pages-face-demo)
