# 服务器镜像、构建缓存与日志保留

2026-11-02 排查发现，118 GiB 系统盘已使用 93%，其中 `/var/lib/containerd` 约 90 GiB：镜像内容约 23 GiB、解压和构建文件层约 67 GiB。Docker 当时保留 125 个镜像，当前使用 9 个；三个业务服务累计 115 个版本。业务文件日志约 210 MiB、Docker 控制台日志约 300 MiB、系统日志约 891 MiB。

此前 Jenkins 的 20 次构建记录、5 份归档保留策略只清理 Jenkins 自己的记录与归档，不会删除 Docker 镜像。每次发布的独立版本标签一直保留，构建缓存也没有定时回收。Docker 的镜像与构建缓存统计包含共享层，不能直接相加估算可释放空间。

## 2026-11-02 现场执行结果

- 删除 109 个旧业务镜像（106 个发布版本、3 个初始 `local` 镜像），共保留 16 个镜像，其中包括三个应用的当前版 `474f829…-48`、回滚版 `03b2371…-45`，以及基础组件镜像。
- 构建缓存清理报告回收 39.46 GB；清理后缓存统计仍含与保留镜像共享的 21.08 GB，私有缓存约 4.1 GB。共享统计不能直接当作额外物理占用。
- 系统盘使用率从 93% 降至 40%，可用空间从约 8.8 GiB 增至 68 GiB；系统归档 journal 另清理约 345.5 MB。
- 清理前后全部 12 个现有容器的 ID、镜像、启动时间和运行状态一致，发布历史未变，6 个当前及回滚镜像均仍可检查。数据库查询、两个业务就绪检查、公网 200 探针和预期的未登录 401 均通过。
- 两个项目维护 timer 和系统 `logrotate.timer` 均已启用。现场核查记录保存在服务器 `/var/lib/tradepass-maintenance`，执行日志可通过 `journalctl` 查看。

## 镜像与缓存

维护入口为 `scripts/server/maintenance.py`，默认只预览；只有 `--apply` 才执行删除。自动任务每天北京时间 03:30 运行，保留当前和回滚镜像，遇到构建、发布、异常健康状态或不完整历史记录时停止清理。

镜像只清理 `tradepass-identity`、`tradepass-business`、`tradepass-gateway` 的历史版本。所有现存容器（含停止的容器）引用的镜像、发布历史中的 current/previous、最近 24 小时创建的镜像以及非本项目镜像均受保护。发布锁和中断事务检查用于避免清理发布中的版本。删除使用 Docker API，不直接删除 containerd 文件，不删除容器或数据卷。

构建缓存保留最近 24 小时的记录，清理时以 4 GB 为保留目标。Docker 可能保留共享、活动或受其他引用保护的层，4 GB 不是整个 containerd 目录的硬上限。清理会影响后续缓存命中率，后续构建可能需要重新执行部分步骤；运行中应用不依赖可回收的构建缓存。

```bash
# 先看保留版本及候选清单
python3 scripts/server/maintenance.py \
  --history-dir /docker/tradepass/jenkins/jenkins \
  --min-image-age-hours 24 --cache-age-hours 24 --cache-keep-storage 4GB

# 按相同规则执行
python3 scripts/server/maintenance.py \
  --history-dir /docker/tradepass/jenkins/jenkins \
  --min-image-age-hours 24 --cache-age-hours 24 --cache-keep-storage 4GB --apply
```

history-dir 必须是当前发布器实际保存 `history.json`、`publish.lock` 和事务记录的目录。该路径由实际 Compose 的父目录下再加 `jenkins` 得出，不能仅凭仓库目录猜测。历史文件缺失或与运行版本不一致时，先核对发布记录，不要通过绕过校验强制删除。

## 文件日志

应用现有文件日志按日期分卷、单份约 10 MB、保留 30 天。Logback 模板补充每服务 1 GB 归档上限及启动时清理，随后续正常应用发布生效。

当前运行实例由独立的 `scripts/server/cleanup_logs.py` 每小时清理归档，无需重启应用：

```bash
# 默认只预览
python3 scripts/server/cleanup_logs.py --directory /docker/tradepass/logs

# 保留 30 天、每服务归档容量 1024 MiB
python3 scripts/server/cleanup_logs.py --directory /docker/tradepass/logs \
  --retention-days 30 --archive-cap-mib 1024 --apply
```

该脚本只识别 `tradepass-{identity,business,gateway}.log.YYYY-MM-DD.N.log`（含可选 `.gz`）的普通文件。按天过期时，文件名日期和修改时间都必须早于保留窗口；容量超限时从最老归档开始删除，但保留当日归档、未来日期归档及当日修改过的文件。活动的 `tradepass-*.log`、符号链接和其他文件不在删除范围，删除前再次核对文件身份和修改时间。当前受保护归档已超容量时只报告，不触碰当日文件。

同一日志任务还通过 `journalctl --vacuum-time=30d --vacuum-size=512M` 清理系统已归档 journal，活动 journal 保留，因此 512 MB 是归档清理目标而非系统日志总量硬上限。安装器同时启用系统已有的 `logrotate.timer`，按系统原有规则轮转其他系统日志。

## Docker 控制台日志

当前三个 Java 服务、Nginx 和 Jenkins 的旧容器没有原生轮转上限。新建容器模板已统一使用 `json-file`、每份 `10m`、保留 `3` 份；已有 Jenkins 模板原本就有这一设置。发布器从旧容器生成运行快照时会补齐缺省的 `max-size`/`max-file`，显式自定义值和其他日志驱动保持原值。

修改配置不会立即改变运行中容器。Docker 官方说明，既有容器要重新创建才能采用新的日志选项，且外部工具不应直接修改 Docker 管理的 JSON 日志文件。因此本次不中断服务，不截断、移动或外部轮转这些文件，也不重启 Docker。三个应用的上限在下次正常发布时生效；Nginx/Jenkins 在各自正常维护、按已更新配置重新创建时生效，单纯 `restart` 不会应用新选项。

现场还备份并校验了 `/etc/docker/daemon.json`，保存同样的默认 `json-file` 轮转参数；为避免影响运行服务，本次未重启或重载 Docker，这份守护进程默认配置在下次正常重启 Docker 后才对新容器生效。应用发布器和 Logback 的源码修改需按现有代码提交流程发布；本次已安装的维护定时任务独立于应用版本运行。

官方依据：[配置日志驱动](https://docs.docker.com/engine/logging/configure/)、[JSON 文件日志驱动](https://docs.docker.com/engine/logging/drivers/json-file/)。

## 安装与核查

安装器 `scripts/server/install-maintenance.sh` 安装两个 systemd 定时任务，镜像/缓存每日 03:30、日志归档每小时运行。所有日期按 `Asia/Shanghai` 解释。安装和手动运行的结果应结合 systemd 日志核查；不能将“定时器存在”当作本次删除已成功的证据。

```bash
# 在服务器仓库中运行；安装器不重启 Docker 或应用
sudo bash scripts/server/install-maintenance.sh
systemctl list-timers --all 'tradepass-*' 'logrotate*'
journalctl -u tradepass-maintenance.service -u tradepass-log-cleanup.service
```

任务使用较低 CPU/IO 调度优先级，镜像任务不会启动被手动停止的 Docker。发布通过既有 `publish.lock` 互斥；构建采用进程检测、24 小时镜像保护及 Docker 自身引用保护，检测到构建时跳过，不代表与所有 CI 构建全程共用锁。多标签、外部仓库引用及不符合项目发布命名的镜像默认保留；三个业务仓库的旧 `:local` 标签也按同一套保护规则清理。

检查应包含清理前后的 `df -h /`、`docker system df`、各服务容器的 ID/启动时间/健康状态，以及历史中当前和回滚镜像仍能 `docker image inspect`。`/tcb_probe` 应返回 200，未登录 `/api/me` 应返回 401，再用小程序确认业务刷新。

日志清理任务自身只输出文件名、大小和汇总，不读取日志正文。维护任务不清理数据库、MQ、Redis、Nacos 或 Jenkins 数据卷，不使用 `docker system prune` 或 `docker volume prune`。
