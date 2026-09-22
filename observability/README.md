# 第一阶段运行监控

## 已提供

- 应用端 Actuator 健康探针和 Prometheus 指标。
- Prometheus 采集配置、四条告警及规则测试。
- 可导入的 Grafana 运行看板：`grafana/tradepass-runtime.json`。

这是一组配置和应用能力，尚未部署监控服务器，也没有接通任何告警通知渠道。ELK、SkyWalking 和业务专用指标在后续服务拆分阶段接入。

## 应用端开启

在原 profile 后加 `observability`，例如本地开发使用 `dev,observability`。继续使用现有环境变量和启动命令，不要为开启监控切换业务 profile。

默认管理地址是 `127.0.0.1:10099`，业务端口仍为 `9999`。可通过 `MANAGEMENT_ADDRESS`、`MANAGEMENT_PORT` 指定受控的内网监听地址。容器/K8s 中监听 `0.0.0.0` 时应由私有网络和访问策略隔离，不能把管理端口发布到公网。

| 路径 | 用途 |
|---|---|
| `/actuator/prometheus` | JVM、HTTP、连接池等指标 |
| `/actuator/health/liveness` | 进程存活；不因数据库故障反复重启应用 |
| `/actuator/health/readiness` | 接流量准备状态，包含数据库连通性 |

健康响应仅展示状态，不输出配置或凭证。`env`、`configprops`、`heapdump`、`beans`、`loggers`、`shutdown` 未开放。Redis 仍是可选缓存，不作为当前就绪探针的强依赖。

## Prometheus 与看板

从 `prometheus/` 目录启动 Prometheus，或挂载整个目录并设定工作目录。规则和 file_sd 路径相对于运行工作目录。

1. 将真实管理地址写入 `targets.json`，格式参考 `targets.example.json`。示例的 loopback 地址只适用于 Prometheus 和应用运行于同一主机网络；容器内需要配置可达的私网地址。
2. `prometheus.yml` 的 Alertmanager 目标列表当前为空。必须配置实际 Alertmanager 和通知接收器，并执行告警送达演练，才能依赖通知能力。
3. 在 Grafana 导入 `grafana/tradepass-runtime.json`，选择 Prometheus 数据源。

看板展示请求速率、5xx 速率、HTTP P95、JVM 堆、数据库连接和 CPU。当前 P95 汇总了普通 API 和文件 API，适合运行概览；后续按业务接口类型划分 SLO。指标标签禁止加入用户、企业、订单或合同 ID。

## 告警规则

| 告警 | 条件 |
|---|---|
| 采集目标缺失 | 5 分钟没有 tradepass 采集目标 |
| 实例不可达 | 连续 2 分钟采集失败 |
| HTTP 5xx 升高 | 5 分钟窗口错误比例超过 5%，至少 20 个请求，持续 5 分钟 |
| JVM 堆压力 | 堆使用超过 85%，持续 10 分钟 |

这些是初始运行阈值，不代表已经承诺的业务 SLA。需要根据实际流量调整，并加入回调积压、合同归档失败、库存/账款异常等业务指标。

```bash
cd observability/prometheus
promtool check config prometheus.yml
promtool test rules alerts.test.yml
```

CI 使用固定工具镜像执行离线语法和规则验证；该镜像版本不作为生产部署版本推荐。Grafana JSON 已做结构校验，尚未在真实 Grafana 实例导入演练。
