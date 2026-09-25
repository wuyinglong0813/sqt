# 普通腾讯云 COS 接入

本次新增 `tradepass.storage.provider: tencent-cos`，直接使用 COS Java SDK，不依赖微信云托管临时凭据接口。当前接入桶为 `sqt-1461413991`，地域 `ap-shanghai`，保持私有读写。新文件写入 `tradepass/` 前缀，现有 MySQL BLOB 文件继续按原路径读取，不做历史数据搬迁。本次不接入其他旧 COS/OSS 桶。

## 配置准备

配置位于 Nacos public namespace（空 ID）、`TRADEPASS_CORE` 组、`tradepass-business.yaml`。参考 [可合并的配置片段](../deploy/server/nacos/tencent-cos.example.yml)，只替换原有 `tradepass.storage` 节点，保留 `tradepass` 下其他节点以及 datasource、RocketMQ 等配置；不要直接覆盖整个 Data ID，也不要创建重复的 `tradepass` YAML 键。

在 Nacos 私下填写 `tradepass.storage.cos.secret-id` 和 `secret-key`。模板留空；缺少凭据时启用存储会导致应用启动失败。普通密钥的 `session-token` 留空。若填入临时凭据，需要同时填写三项，并在到期前更新 Nacos 和重启 business；当前此适配器不自动刷新临时凭据。

使用专门的 COS 子账号凭据，授权范围限定为这个桶及 `tradepass/*`。本适配器使用 `cos:PutObject`、`cos:GetObject`、`cos:HeadObject`，并通过 `cos:GetBucketVersioning` 查询桶的版本状态；上传时携带私有 ACL 和 AES256 服务端加密请求头。无需删除对象或修改桶版本控制的权限。正式密钥只填写在服务器的 Nacos，勿提交到仓库。

本实现为保证同名文件不被覆盖，要求桶的版本控制从未开启（SDK 状态 `Off`）。对 `Enabled`、`Suspended` 或查询失败均拒绝上传，不会擅自改变桶设置。COS 在开启/暂停版本控制后不能依赖 `x-cos-forbid-overwrite` 阻止覆盖，参见 [PUT Object](https://cloud.tencent.cn/document/product/436/7749)。加密设置依据 [Java SDK 服务端加密](https://cloud.tencent.com/document/product/436/47765)，凭据初始化依据 [Java SDK 快速入门](https://cloud.tencent.com/document/product/436/10199)。

## 部署顺序

必须先部署包含新适配器的 business 镜像。仅执行 `business.sh restart` 不会更新镜像；不要对旧镜像先发布 `provider: tencent-cos`。

在有本次代码的构建环境、仓库根目录执行：

```bash
mvn -B -pl tradepass-business -am -DskipTests package
docker build -f tradepass-business/Dockerfile -t tradepass-business:local .
```

镜像需与服务器 CPU 架构一致。可以在服务器取得代码后构建；也可以在本地构建 JAR，再在服务器用同一 Dockerfile 打包镜像。上述 `-DskipTests` 只打包，不代表测试已通过。

待服务器已具备新镜像后：

1. 在 Nacos 的 `tradepass-business.yaml` 中合并模板并填入私有凭据，发布配置。
2. 在服务器仓库 `deploy/server` 目录执行以下命令，按新镜像重新创建 business 容器，保留其数据库和挂载配置：

```bash
docker compose -f .runtime/core.compose.yml up -d --no-deps --force-recreate --wait --wait-timeout 360 business
```

3. 执行 `business.sh check`，再用小程序上传一份小附件并重新打开，检查 COS 桶中新增对象；打开一份历史附件验证原数据库内容仍可读取。应用健康检查通过不代表已经完成 COS 鉴权和文件读写验收。

后续仅修改 Nacos 中 COS 配置时用 `business.sh restart` 即可。业务文件仍通过后端鉴权读取，本次不要求小程序直接访问 COS 默认域名，也不生成公开文件链接。

## 验证命令

对象存储测试（SDK 调用使用 mock，不使用真实 COS 凭据）：

```bash
mvn -B -pl tradepass-module-file/tradepass-module-file-server -am \
  -Dtest=TencentCosObjectStorageServiceTest,CloudBaseCosObjectStorageServiceTest,AliyunOssObjectStorageServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

测试覆盖配置绑定和 provider 选择、私有加密上传、版本控制限制、摘要与长度校验、同名冲突、缓存下的实际回读验证和错误处理。真实 COS 的账号权限、网络、上传和历史附件读取需在部署后验收。
