# 服务器 Compose 部署

本目录部署四个独立业务库、Seata、RocketMQ、XXL-JOB，以及 identity、contract、trade、settlement、file、gateway 六个应用。业务镜像使用 JDK 17。服务器有 Docker/Compose 即可；完整迁移和运维说明见 [分库切换手册](../../docs/server-microservices-cutover.md)。

## 准备

保留目录内的 `mysql/`、`seata/`、`rocketmq/`、`xxl-job/` 配置及 SQL。复制 `.env.example` 为私有 `.env`，填写所有数据库密码、服务密钥和调度 token。各数据库密码及 XXL-JOB 管理密码分别执行 `openssl rand -hex 32` 生成；Seata signing key 使用 `openssl rand -base64 32`。`.env` 权限设为 600。

数据库变量已分为 `IDENTITY_*`、`CONTRACT_*`、`TRADE_*`、`SETTLEMENT_*` 四组。旧共享 `TRADEPASS_DATABASE_URL/DB_PASSWORD` 不再是这些 Compose 的部署输入。已有数据库卷保持原数据和密码，不会自动创建新库或转换旧数据。

首次创建私有网络：

```bash
docker network create tradepass-server
```

## 基础设施

```bash
docker compose --env-file .env -f infra.compose.yml --profile mq --profile jobs up -d --wait --wait-timeout 360
docker compose --env-file .env -f infra.compose.yml --profile mq --profile jobs ps -a
```

- 首次初始化 MySQL 时创建四个独立库、各自账号和 XXL-JOB 管理库；各账号只授权对应库。
- Seata 2.1.0 使用持久卷保存协调日志，私网地址 `seata:8091`。单节点配置用于单机环境；高可用需独立设计协调器存储与访问地址。
- RocketMQ 5.3.4 的 namesrv 地址 `rocketmq-namesrv:9876`，Broker 使用当前容器 IP 注册；`rocketmq-init` 初始化卷权限，`rocketmq-topic-init` 创建回调 topic 和消费组。这两个一次性容器成功后显示 `Exited (0)`。
- XXL-JOB 3.2.0 使用 `xxl-job-admin:8080/xxl-job-admin`，账号 `tradepass`、密码 `XXL_JOB_ADMIN_PASSWORD`。自动创建的 `fadadaCallbackRecovery` 每 30 秒运行一次；执行器 token 与合同服务保持一致。
- 中间件没有发布宿主机业务端口。此单机配置不包含跨主机 ACL/TLS 或高可用部署。

基础设施就绪后，先按迁移手册处理历史数据，再启动业务；空的新环境也可由各服务自己的 Flyway 基线建表。不得把新服务接回共享旧库。

## 业务服务

构建或加载经过验证的六个镜像，设置同一 `TRADEPASS_IMAGE_TAG`：

```bash
docker compose --env-file .env -f service.compose.yml up -d --no-build --wait --wait-timeout 360
docker compose --env-file .env -f service.compose.yml ps
```

固定配置 `pull_policy: never`，防止服务器意外拉取同名镜像。正式流水线使用摘要固定的发布包。网关默认只绑定 `127.0.0.1:1110`；`file` 只接收存储凭据、不接收数据库凭据。

合同 profile 默认 `observability,messaging,jobs`。使用 Nacos/Sentinel 时设置公共 profile 为 `observability,nacos,sentinel`，合同 profile 为 `observability,nacos,sentinel,messaging,jobs`，并先导入独立环境的 Nacos 配置。

## HTTPS 与可观测性

将 `fullchain.pem` 和 `privkey.pem` 放到私有目录，设置 `TRADEPASS_TLS_DIRECTORY`，再运行 `edge.compose.yml` 启动 Nginx HTTPS 入口。证书必须匹配实际服务域名。

Prometheus/Grafana/Alertmanager 见 `../microservices/monitoring`；ELK/Filebeat/SkyWalking 见 `../../observability/extended.compose.yml`。启用调用链时配置 `TRADEPASS_TRACING_ENABLED=true` 和 OAP 地址。告警接收人、邮件或 IM 通道由环境配置提供。

## 重启与数据保留

重启使用原 `.env`、网络、项目名和持久卷。不要用 `down -v` 清理业务或中间件数据。分库切流后的回退需要数据回流方案，旧共享库快照不能直接代替新业务库。发布脚本只能回退兼容同一套已迁移数据库的服务镜像。
