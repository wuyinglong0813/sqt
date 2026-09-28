# RocketMQ 资源初始化与验收

资源清单位于 `deploy/server/rocketmq/resources.json`。当前生产源码有一个 MQ 适配器：合同回调使用一个 Topic、一个消费组。生产者组是客户端标识，无需作为 Broker 订阅组预建。RocketMQ 自带系统 Topic 以及运行时产生的重试、死信 Topic 不属于这份业务资源清单，盘点会列出它们，不会删除。

此次修复解决的部署缺口：旧 topic-init 通过 `-c DefaultCluster` 从 NameServer 获取 Broker 地址。core 为 host 网络业务进程注册 `127.0.0.1:10911`，独立初始化容器按这个地址连接会连到自身。此外，RocketMQ 5.3.4 的 mqadmin 某些异常路径只打印异常，进程仍退出 0，`sh -e` 无法保证初始化成功。`rocketmq-init` 只负责数据目录权限，与 topic-init 是不同任务。

## 固定机制

- 初始化直接连接 `rocketmq-broker:10911`。使用镜像内 SDK 操作，异常返回非零；不根据 mqadmin 退出码判断成功。
- 遍历版本化清单，只创建缺失的 Topic/消费组，再回读 Broker 配置和 NameServer 路由。已有资源队列数至少达到清单要求，读写权限、消费开关和 Broker 身份必须正确；配置异常停止，不覆盖已有资源，不删除额外资源，不修改消息或消费位点。
- `configure-core-nacos.py` 生成 `.runtime/rocketmq/resources.json`，按合并后的 Nacos 原生属性解析名称和 ACL，不使用过期 `.env` 的业务配置覆盖 Nacos。私有清单权限 600，只读挂入初始化容器。
- core 发布/重启 business 前执行同一清单的 `ensure`，检查失败时不会停止运行中的应用。SSH 发布包同时携带检查程序与清单。
- `scripts/server/all.sh start` 等启停脚本在 Broker 健康后执行 `ensure`；`all.sh check` / `business.sh check` / `infra.sh check` 会核对资源。`status` 仍只显示容器状态。启动流程需要现有业务容器的 Nacos 挂载；首次部署使用 Compose 初始化任务，或显式 `--bootstrap`。
- 新版 business 的 `/actuator/health/readiness` 包含 `callbackMessaging`，根据应用实际生效的 Topic/消费组，验证 NameServer 路由、上报地址能否连接、Broker Topic 可读写及消费组可用。端口通不再等同于业务就绪。`messaging` profile 要求启用 MQ。
- CI 检查生产 MQ 适配器必须登记在清单中，并在关闭自动建 Topic/消费组的隔离 Broker 上验证：全量缺失检测、两个资源同时初始化、重复执行、错误 Broker 拒绝、额外资源保留、消费组禁用时拒绝覆盖。

## 服务器执行

在服务器现有后端 Git 仓库根目录拉取代码，再执行盘点与验收。依赖 Python 3、PyYAML、Docker 和官方 RocketMQ 5.3.4 镜像中的 JDK。日常更新使用 Git 与现有发布流程。

```bash
git pull --ff-only origin main

# 只读盘点：全部 Broker Topic/消费组 + 项目清单逐项验收。
# 读取业务容器挂载的 bootstrap，再只读获取 Nacos common/business 配置。
python3 scripts/server/mq_resources.py audit

# 正式初始化：遍历完整清单，补建缺项，回读验证；可重复执行。
python3 scripts/server/mq_resources.py ensure

# 资源 + 容器整体验收。
bash scripts/server/all.sh check
```

首次部署尚无业务容器时，可以传 `--bootstrap deploy/server/.runtime/nacos/bootstrap.yml`。检查程序限定 core 单机部署：NameServer `127.0.0.1:9876`、Broker `127.0.0.1:10911`；配置源、容器端口映射或 Broker 身份不一致会停止，不会转向默认集群继续补建。Nacos 无法读取也不会回退到默认名称。清单是待部署的 Nacos 配置；已运行应用的配置由它自身的 readiness 探针验证，Nacos 配置不会自动刷新到旧进程。

`audit/check` 不修改 MQ 配置，但会临时复制、编译检查程序，完成后清理自己的临时文件。`ALL_TOPICS` / `ALL_CONSUMER_GROUPS` 是全量列表，逐项结果全部为 true 且出现 `MQ_RESOURCES_OK` 才表示通过，失败返回非零。存在额外资源不视为错误；缺少预期资源或配置漂移会报错。

同步新版脚本后，现有部署可先执行完整清单 `ensure`；随后正常发布 business，应用探针才会生效。旧 `.runtime/infra.compose.yml` 不会自动改写，重新生成启动配置时才应用新 topic-init 定义；现有部署的发布前检查独立于旧初始化容器，仍会执行。

## 不属于此次资源修复的事项

这不直接修改 `fadada_callback_event`、企业认证状态或企业成员关系。现场两条回调停留 `RECEIVED/attempt_count=0`，需要在部署后确认消费或后台补偿是否推进；企业的“缺少经办人身份”仍需核实回调处理和法大大返回值，不能直接改成认证成功。

## 本地验证

```bash
python3 -m unittest discover -s scripts/ci/tests -v
python3 scripts/ci/test_mq_resources.py
mvn -B -pl tradepass-module-contract/tradepass-module-contract-server -am \
  -Dtest=CallbackMessagingHealthIndicatorTest,CallbackInfrastructureTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```
