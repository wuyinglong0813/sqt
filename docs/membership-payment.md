# 企业 VIP 和签署包购买接入

已实现：企业套餐页、开通/续费 VIP、独立签署包、企业订单记录、支付确认、未付订单关闭、微信支付 APIv3 验签/解密、回调幂等开通、漏回调查单补偿。代码没有测试或模拟支付成功的公开入口。实际付款需要补齐商户参数并开通适用的支付能力。

## 全部配置的位置

两个独立 Nacos YAML 文件，与 business 处于同一 namespace，默认 Group 为 **TRADEPASS_CORE**：

| Data ID | 内容 |
| --- | --- |
| tradepass-membership.yaml | 体验、名单、收费开关、商品价格、首次优惠、会员期限及签署份数 |
| tradepass-payment.yaml | 支付通道、商户号、AppID、证书序列号、签名私钥、APIv3密钥、微信验签材料、回调地址、允许客户端 |

分别复制 [会员模板](../deploy/server/nacos/membership.example.yml) 和 [支付模板](../deploy/server/nacos/payment.example.yml)。保留现有体验与名单内容，将 products 和 order-expire-minutes 合并进去。真实密钥只填在 Nacos，不放前端、Git、聊天或普通会员策略文件；支付文件不会复制进会员策略的数据库审计快照。

时间继续使用带引号的北京时间 **yyyy-MM-dd HH:mm:ss**，例如 "2026-12-31 23:59:59"。价格按人民币分填写：69900 对应 699 元。实际业务订单和消费记录保存在数据库，不能放到 Nacos。

## 商户参数清单

1. 普通商户小程序支付的 mch-id。
2. 与该商户绑定的小程序 app-id，必须与 common 中的 wechat.app-id 一致。
3. 商户 API 证书序列号 merchant-serial。
4. 商户 API 私钥的完整 PKCS#8 PEM（含 BEGIN/END 行）。
5. 32字节 APIv3 密钥，与旧版 API 密钥区分。
6. 微信支付公钥 ID 和对应公钥 PEM；也支持使用对应的微信支付平台证书序列号及证书 PEM。
7. 已获准使用该支付通道的客户端列表 allowed-platforms。

PEM 在 Nacos 中可以这样粘贴：

~~~yaml
    merchant-private-key-pem: |
      -----BEGIN PRIVATE KEY-----
      在Nacos填写实际内容
      -----END PRIVATE KEY-----
    verification-key-pem: |
      -----BEGIN PUBLIC KEY-----
      在Nacos填写实际内容
      -----END PUBLIC KEY-----
~~~

示例中的中文占位符不能原样开启支付。未补齐参数时保持 payment.enabled 和 membership.billing.enabled 为 false。

本版通道是 WECHAT_JSAPI，默认 allowed-platforms 为空。须根据实际服务类目和获准的客户端能力填写；不是填写普通商户参数后就自动适用于所有数字会员或全部终端。若实际获准通道是虚拟支付，需要适配对应通道，不通过伪装客户端绕过限制。

## 商品配置

~~~yaml
    billing:
      enabled: false
      order-expire-minutes: 15
    products:
      - id: vip-year
        name: 企业VIP年会员
        type: VIP
        price-fen: 99900
        first-price-fen: 69900
        vip-days: 365
        sign-quota: 100
        quota-days: 365
        on-sale: true
      - id: sign-10
        name: 电子签10份
        type: QUOTA
        price-fen: 9900
        sign-quota: 10
        quota-days: 365
        on-sale: true
~~~

- VIP：支付核实后开通会员，并发放包含的签署额度。续费从当前仍有效的会员结束时间顺延；已到期则从到账确认时起算。
- QUOTA：只发放签署额度，不强制开通 VIP；没有 VIP 也可以购买普通签署包。
- first-price-fen：仅首次付费开通 VIP 适用，不影响后续续费。企业同一时间只允许一笔未完成 VIP 订单，避免同时占用多笔首次优惠。
- vip-only：仅当前有效的付费 VIP 可购买，免费体验和白名单身份不自动获得该付费增购优惠。
- on-sale：商品上下架，不改变已有订单的价格和权益快照。
- vip-days、quota-days 分别管理会员和每批额度期限。续费包含的额度立即到账，按本批 quota-days 到期，界面显示准确到期时间；不清空老额度。

商品只承诺已能交付的权益；示例主要是 VIP 有效期、包含份额和付费增购优惠，不将未上线的高级功能写成已可用。

## 一次更新与后续启用

1. 先更新 identity：新增从当前已登录用户读取支付身份的内部接口，付款人 OpenID 不接收前端传入。
2. 更新 business：Flyway 自动执行 business V5（contract 分库 V4，历史布局 V40），增加购买订单及支付事件表。
3. 更新小程序。现有 gateway 已有 /api/membership/** 路由，无需新增路由；如网关仍是会员功能前的旧版本，需要同时更新 gateway。
4. 在 Nacos 补齐两个文件，先保持收费关闭。确认商户绑定、服务类目、客户端及公网回调可用后，分别开启 payment.enabled 和 membership.billing.enabled。
5. 使用真实商户和实际允许的客户端做小额完整交易、核对交易账单与权益，再开放正式商品。

代码构建：

~~~bash
mvn -B -pl tradepass-module-identity/tradepass-module-identity-server,tradepass-business,tradepass-gateway -am package
~~~

更新后的两个专用配置都支持动态监听，并每30秒补充核对，不把其他数据库或密钥配置一起热刷新。关闭新收费不停止已支付订单的处理。保持在途订单所需支付密钥可用；删除文件仅在当前进程保留旧验证材料，重启后仍需要从 Nacos 读取完整参数。

## 支付和权益保证

- 法人、管理员或拥有现有企业管理权限的成员购买，权益归属经核验的当前企业主体。
- 创建订单固定价格、会员天数、签署份额、额度有效期、商户/AppID及付款人；不信任客户端价格、OpenID或支付成功回调。
- 重试同一下单请求使用相同幂等键，复用订单。支付前显示后端订单实际价格与企业，防止配置改价后误付。
- 真正预支付前写入尝试记录，防止另一管理员同时关闭未开始订单时引入竞态。
- 回调验证微信 RSA 签名、时间戳、公钥标识，并以 APIv3 密钥做 AES-GCM 解密；匹配订单号、AppID、商户号、付款人、币种、金额和真实成功状态。
- 验证通过后事务内开通会员、发放额度、更新订单。重复回调、并发回调和查单只开通一次；关闭收费或后续加入黑名单不吞掉真实付款。
- 回调入口为 POST /api/membership/payment/notify，仅该入口不要求用户登录；任何未验签支付结果都不能授予权益。
- 小程序支付取消不立即删除订单；实际到账状态以后端核实为准。可继续支付、刷新结果或关闭未支付订单。
- 后台每30秒核对最多10笔待支付订单，处理漏通知及过期未付订单。异常状态保留待核实，不能根据网络失败伪造关闭或成功。
- 没开始预支付的订单可直接受控关闭；已开始的先查询微信状态。签名验证确认订单不存在且至少一分钟没有新的预支付尝试后才允许关闭；真实迟到支付仍按原订单兑现。

退款、发票签发及平台售后管理不是本版自动处理范围，需按实际商户和业务规则接入；本版不会伪造退款成功或发票。

## 接口与排查

接口均归属 business，除微信回调外要求登录：

- GET /api/membership/products?platform=android：可选套餐与购买权限。
- POST /api/membership/orders：productId、idempotencyKey、platform。
- GET /api/membership/orders：当前企业最近50笔购买记录，需企业管理权限。
- GET /api/membership/orders/{no}：订单、到账状态和各权益期限。
- POST /api/membership/orders/{no}/prepay：经检查后生成实际微信预支付参数。
- POST /api/membership/orders/{no}/sync：核实微信结果。
- POST /api/membership/orders/{no}/close：核实并关闭未支付订单。

价格单位为分，页面按元展示。购买暂未开放时检查两处开关、参数完整性、商品上架、企业权限、客户端列表及是否属于免费无限名单。不要为了测试打开模拟付款或直接改订单为 PAID。

实现依据：[微信小程序下单](https://pay.wechatpay.cn/doc/v3/merchant/4012791897)、[微信支付回调](https://pay.wechatpay.cn/doc/v3/merchant/4012791861)、[微信支付公钥验签](https://pay.wechatpay.cn/doc/v3/merchant/4013053249)。
