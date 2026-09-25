# 服务器日常启停与配置位置

适用于已有的三进程部署：gateway、identity、business，以及 MySQL、Redis、Nacos、RocketMQ、Nginx。入口是 `scripts/server/all.sh` 和各个 `服务名.sh`，共用同目录的 `control.sh`、`tradepass.py`。依赖 Bash、Python 3 标准库和 Docker CLI，不需要安装 Python 包。请在服务器运行，Docker CLI 应连接当前服务器的 daemon。

## 使用方式

进入服务器仓库的 `scripts/server` 目录执行。也可以将这些入口和 `control.sh`、`tradepass.py` 一起复制到独立目录使用；支持从任意工作目录通过绝对路径运行。不要只上传一个入口文件。

```bash
# 全部服务
./all.sh start
./all.sh stop
./all.sh restart
./all.sh status
./all.sh check

# 单个服务，把 business 换成其他服务名即可
./business.sh start
./business.sh stop
./business.sh restart
./business.sh status
./business.sh check

# 只重启三个 Java 应用，例如修改了 Nacos common 配置之后
./apps.sh restart

# 基础设施分组
./infra.sh status

# 先查看顺序，不连接 Docker、不执行操作
./all.sh restart --dry-run

# 不带参数默认查看状态；查看帮助
./all.sh
./business.sh --help
```

可用服务：`mysql`、`redis`、`nacos`、`rocketmq-namesrv`、`rocketmq-broker`、`identity`、`business`、`gateway`、`nginx`，各有同名的 `.sh` 文件。分组 `apps.sh` 是三个 Java 应用，`infra.sh` 是五个常驻基础组件，`all.sh` 还包含 Nginx。如果上传工具丢失执行权限，可以用 `bash all.sh start` 等方式运行。

- `start`：自动启动缺失的依赖，依次等待健康；已健康的容器不会被重启。全部顺序为 MySQL → Redis → Nacos → NameServer → Broker → identity → business → gateway → Nginx。这个顺序用于恢复，Nacos 本身并不依赖当前业务 MySQL。
- `stop`：只停止所选服务，按上述顺序倒序停止；默认每个容器给予 90 秒优雅退出时间。停止单个基础组件会影响仍运行的应用，不自动连带停掉应用。
- `restart`：倒序停止所选服务，再补齐依赖并正序启动；例如 `./business.sh restart` 只重启 business，健康的依赖保持运行。
- `status`：显示运行状态、健康状态、最近退出码和 OOM 标记；容器已停止不视为命令错误。
- `check`：上述检查中任何服务未就绪时返回退出码 1，全部就绪返回 0；Nginx 当前没有 Docker healthcheck，仅验证运行状态。容器健康不能代替真实业务验证。

每个服务默认最多等待健康 360 秒，可加 `--timeout 600`；停止等待可加 `--stop-timeout 120`。命令失败即停止后续启停，已完成的操作保留，不自动回滚。一次只运行一个启停命令，不要并发操作同一套服务。退出码 2 表示参数有误。

脚本使用当前部署的固定容器名（`tradepass-core-*`、`tradepass-infra-static-*`、`tradepass-edge-nginx-1`），操作前检查所需容器是否存在。仅恢复已有容器，不创建、删除、重建容器，不拉镜像、不改配置或数据卷。已删除的容器或新环境应先按 [部署手册](server-core-cutover.md) 使用私有 `.runtime/*.compose.yml` 创建。`rocketmq-init` 和 `rocketmq-topic-init` 是一次性初始化任务，不属于日常启停列表。

健康检查失败时在服务器本地查看对应日志，例如：

```bash
docker logs --tail 100 tradepass-core-business-1
docker stats --no-stream
free -h
```

公网入口验证：

```bash
curl -i --max-time 10 https://sqt.org.cn/tcb_probe
curl -i --max-time 10 https://sqt.org.cn/api/me
```

预期分别为 200 和未登录 401；再用小程序验收登录及业务操作。脚本不会注册开机自启，当前应用的 `restart: "no"` 策略保持原样。

## Nacos 与 MySQL 密码分别在哪里

以下路径以服务器仓库根目录为起点。应用配置已迁移到 Nacos 后，配置分成基础设施启动、连接 Nacos 的引导配置、Nacos 中的业务配置三部分。

| 内容 | 当前位置 | 用途 |
| --- | --- | --- |
| MySQL root 密码 | `deploy/server/.runtime/infra.compose.yml` 中 MySQL 的 `MYSQL_ROOT_PASSWORD` | MySQL 首次初始化和容器健康检查；不是应用数据库账号 |
| identity/business 数据库账号密码 | Nacos 的 `TRADEPASS_CORE` 组下 `tradepass-identity.yaml` / `tradepass-business.yaml`，字段 `spring.datasource.username/password` | 应用读取后登录各自数据库 |
| 数据库应用账号的初始密码 | `.runtime/infra.compose.yml` 中 MySQL 的 `IDENTITY_DB_PASSWORD` / `BUSINESS_DB_PASSWORD` | 初始化数据库用户；可能保留旧值，后续改密以数据库真实账号及 Nacos 客户端配置为准 |
| 应用登录 Nacos 的账号密码 | `deploy/server/.runtime/nacos/bootstrap.yml`，`spring.cloud.nacos.config` 与 `discovery` 下的 `username/password` | 应用启动时先登录 Nacos，拉配置并注册服务；当前生成器为两处写入同一组账号密码 |
| Nacos 自身的鉴权 token 与服务端身份参数 | `.runtime/infra.compose.yml` 中 Nacos 的 `NACOS_AUTH_TOKEN`、`NACOS_AUTH_IDENTITY_KEY/VALUE` | Nacos 服务端鉴权配置，不是控制台登录密码 |
| 旧的一次性导入文件 | `deploy/server/.env.core`（如果仍保留） | 历史迁移输入，已生成的运行配置不再引用它；内容可能已过时 |

当前 Nacos Compose 使用 `MODE: standalone` 和 `nacos-data` 数据卷，没有配置连接业务 MySQL。Nacos 的用户记录和配置数据保存在自身持久化存储中；`bootstrap.yml` 保存的是客户端登录所需的凭据，并不是通过编辑这个文件来修改 Nacos 服务端用户密码。

启动过程是：

1. Docker 用 `.runtime/infra.compose.yml` 创建基础容器；已有容器重启时复用 Docker 保存的参数。MySQL 和 Nacos 可以先各自启动，均不需要先向 Nacos 拉取自身启动配置。
2. Java 应用读取挂载的 `bootstrap.yml`，用其中的地址、账号、密码连接 Nacos。
3. 应用从 Nacos 读取 common 和自己服务的 YAML，拿到数据库账号密码、Redis、微信、法大大、存储等配置。
4. 应用用数据库账号连接 MySQL，完成启动并对外服务。

因此，“要先连接 Nacos”指的是 Java 应用。Nacos 的连接密码必须有本地引导来源，否则应用无法完成第一次连接。MySQL 的数据库用户由 MySQL 自己校验，Nacos 只是保存应用使用的那份连接信息。

修改 Nacos 中的数据库密码**不会修改 MySQL 用户密码**；应先协调修改 MySQL 实际账号密码与 Nacos 的 `spring.datasource.password`，再重启相应应用。已有数据卷的 MySQL 也不会因为改了 Compose 的初始化密码变量就自动改密。修改 Nacos 登录密码时，需同时更新本地 `bootstrap.yml` 中 config/discovery 的客户端凭据，再重启应用。普通业务配置在 Nacos 发布后重启对应应用；common 的修改用 `./apps.sh restart`。

如果需要找实际部署目录，可以只查询容器挂载位置和 Compose 文件路径，不输出任何密码：

```bash
docker inspect -f '{{range .Mounts}}{{if eq .Destination "/app/nacos-bootstrap.yml"}}{{println .Source}}{{end}}{{end}}' tradepass-core-identity-1
docker inspect -f '{{index .Config.Labels "com.docker.compose.project.config_files"}}' tradepass-infra-static-mysql-1
```

需要查看明文密码时，仅在服务器自己的终端使用编辑器打开对应私有文件，或登录 Nacos 查看相应配置。私有 `.runtime` 文件不要提交 Git 或贴进聊天；当前生成器将目录设为 700、文件设为 600。Compose 文件中密码里的 `$$` 是转义表示，Docker 解析后的值才是实际值。
