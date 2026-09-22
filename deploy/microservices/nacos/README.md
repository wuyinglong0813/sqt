# Nacos 配置清单

使用 `TRADEPASS_SERVICE_PROFILES=observability,nacos,sentinel` 前，在目标 namespace、`NACOS_GROUP` 中建立以下 Data ID：

| Data ID | 类型 | 初始内容 |
|---|---|---|
| `tradepass-common.yaml` | YAML | 本目录同名文件 |
| `tradepass-identity.yaml`、`tradepass-contract.yaml`、`tradepass-trade.yaml`、`tradepass-settlement.yaml`、`tradepass-file.yaml`、`tradepass-gateway.yaml` | YAML | `tradepass: {configuration-version: 1}`，按服务补充配置 |
| `tradepass-gateway-flow.json` | JSON | `[]` |
| 五个业务服务各自的 `tradepass-<role>-flow.json` | JSON | `[]` |
| 五个业务服务各自的 `tradepass-<role>-degrade.json` | JSON | `[]` |

正常业务配置在启动时读取，修改后滚动重启生效，避免请求中途切换数据源、密钥或存储。Sentinel 的 JSON 规则会动态更新。空数组表示不施加新限流规则，原有权限和业务限流继续生效。控制台临时修改规则不会替代 Nacos 中的持久配置。

网关资源是 `identity`、`contract`、`trade`、`settlement`、`file`，例如压测后为交易路由配置单实例每秒 200 次：

```json
[{"resource":"trade","resourceMode":0,"grade":1,"count":200,"intervalSec":1,"controlBehavior":0}]
```

业务 Feign 资源：

- `POST:http://tradepass-module-identity/tradepass-module-identity-server/internal/identity/resolve`
- `POST:http://tradepass-module-file/tradepass-module-file-server/internal/storage/put`
- `POST:http://tradepass-module-file/tradepass-module-file-server/internal/storage/get`

例如身份调用并发隔离（每个调用方实例最多 50 个并发），写入相应调用方的 `-flow.json`：

```json
[{"resource":"POST:http://tradepass-module-identity/tradepass-module-identity-server/internal/identity/resolve","grade":0,"count":50}]
```

熔断规则写入调用方 `-degrade.json`；以下只是压测起点，不会自动加载：

```json
[{"resource":"POST:http://tradepass-module-file/tradepass-module-file-server/internal/storage/put","grade":1,"count":0.5,"minRequestAmount":20,"statIntervalMs":10000,"timeWindow":10}]
```

网关限流返回 HTTP 429 和原 `code/message/data` JSON；Feign 阻断返回失败，身份验证返回 503，存储异常触发原事务回滚。不能为认证、库存、账款或文件写入配置“成功”降级结果。

Nacos 控制台、HTTP/gRPC 端口及 Sentinel 客户端控制端口仅在可信网络使用。注册 IP 必须能被其他实例访问；容器部署时不要将宿主机回环地址注册到服务目录。现有 ID 组合仍要求每个副本唯一。
