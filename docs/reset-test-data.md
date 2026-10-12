# 清空本系统测试数据后重新认证

适用于当前 gateway / identity / business 三进程部署。脚本为
[`scripts/reset-test-data.sql`](../scripts/reset-test-data.sql)，默认 `PREVIEW`，不删除业务记录。
这不是同一数据库用户 ID 的实名变更：清空 `sys_user` 后，同一个微信再次登录会创建新的本地用户，
生成新的用户 ID 和 `tradepass-user-新ID`。旧企业和全部本地测试业务记录也会清空。

清理范围覆盖当前 56 张应用表，包括后来新增的零售和会员支付表；默认预览并保留系统元数据。
2026-10-12 已在隔离 MySQL 8.4 中按当前 identity/business 迁移建表，填充全部 56 张目标表，验证预览不删数据、清空及重复执行、两库失败回滚，以及未知表/触发器/外键/待恢复事务拒绝执行；权限字典和迁移历史保持完整。
可用 `python3 scripts/ci/test_reset_test_data.py --container <本机隔离CI容器名>` 复验。该测试只创建和删除本次随机生成的 `tradepass_fix_validation_reset_*` 库，不连接服务器业务库。

## 清理范围

- `tradepass_staging_identity`：账号、会话、企业、成员、角色、邀请、个人及企业认证、印章映射、认证申请、合作关系、通知、审计记录。
- `tradepass_staging_business`：合同、签署任务、认证回调、模板、订单、单据、库存、项目、对账、零售客户及单据、会员额度、赠送活动使用量、付费会员、支付订单及回调、会员策略历史等全部本地业务记录。
- `membership_policy_state` 清空后在同一事务中重建 `id=1` 的空初始行；应用启动后从 Nacos 重新导入策略。不能只删除这行，否则会员配置加载会失败。
- 保留：表结构、Flyway 迁移历史、identity 的 `perm_def` 权限字典、business 中现场已有的 `tz_migration` 表及其记录、`undo_log` 事务恢复记录。存在待恢复的事务时拒绝执行。
- 不处理：法大大账户/认证/授权/合同、对象存储文件、Redis、RocketMQ、Nacos 配置、Jenkins。

脚本按仓库的表归属白名单操作，发现缺表、非 InnoDB 表、未知业务表、触发器或尚未移除的外键会报错退出。
删除采用跨两库的一个 InnoDB 事务；删除期间任一 SQL 失败会回滚。脚本不再关闭外键检查，要求先完成去外键升级。
在独立数据库连接中整份执行，不要逐句跳过错误，不要使用 mysql 的 `--force`。
不要修改或删除 `flyway_schema_history`，否则会影响下一次应用启动。

## 操作顺序

1. 在 Nacos 查看 identity/business 数据源的真实库名。脚本默认是上面的两个库；不同则在 SQL 中全文件替换相应库名。
   本脚本不适用于原来的四库或单库部署。两库应在同一 MySQL 实例，以具有两库权限、可创建存储过程并查看外键元数据的账号执行。
2. 在服务器仓库根目录停止三个应用，保留 MySQL、Redis、MQ、Nacos：

   ```bash
   bash scripts/server/apps.sh stop
   ```

   同时停止其他连接这两库的旧应用和人工写入，避免清理过程中产生新记录。法大大回调在停机期间无法被处理，恢复后应使用新一轮认证。
   如果已经开启微信支付，先暂停购买并处理尚未结束的支付订单，再备份和清空；删除本地支付记录不会关闭微信侧的订单，也不会退款。
3. 用数据库工具备份两个完整库，确认备份可用。若使用现有 Docker MySQL，可在服务器仓库根目录执行以下命令。
   它从容器现有环境读取密码，不把密码写入命令行；若此变量与实际 root 密码不一致，请改用数据库工具备份。

   ```bash
   mkdir -p /private-backups/tradepass
   chmod 700 /private-backups/tradepass
   umask 077
   set -C
   docker exec tradepass-infra-static-mysql-1 sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --routines --triggers --no-tablespaces --set-gtid-purged=OFF --databases tradepass_staging_identity tradepass_staging_business' > /private-backups/tradepass/before-reset-20260926.sql
   ```

   要求备份命令退出码为 0 且文件完整；不要重复覆盖已有备份，重复测试应更换文件名。备份含个人及业务数据，不提交 Git。
4. 若尚未部署去外键 V2，先整份执行 [`scripts/remove-all-foreign-keys.sql`](../scripts/remove-all-foreign-keys.sql)，它只移除约束，不清数据。两库 `remaining_foreign_keys` 应均为 0。
   然后执行清空 SQL 的 `PREVIEW` 模式，查看 56 张表的实际记录数。数据库工具中整份执行即可；命令行示例：

   ```bash
   docker exec -i tradepass-infra-static-mysql-1 sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4' < scripts/reset-test-data.sql
   ```

5. 确认清理的两库均为这次测试的数据后，把 SQL 顶部的
   `SET @tradepass_reset_mode = 'PREVIEW';` 改为
   `SET @tradepass_reset_mode = 'CLEAR_ALL_TEST_DATA';`，整份重新执行。
   只有看到 `RESET_COMPLETE` 且客户端无错误，才表示已完成。再改回 `PREVIEW` 执行一次，应显示除 `membership_policy_state` 为 1 行外，其他 55 张目标表均为 0 行；初始策略行应为 `revision=0`、空 `content_hash` 和空 `content`。应用重启后策略及其历史可能重新生成。
6. 旧会话可能缓存于 Redis。保持应用停止，等待超过 Nacos 中实际配置的 `tradepass.redis.auth-cache-ttl`
   （代码默认 60 秒，默认配置可等 65 秒），或删除该部署的登录缓存键
   `<tradepass.redis.key-prefix>:auth:session:*`。代码默认前缀为 `tradepass`。
   不要对共享 Redis 执行 `FLUSHALL`；不需要清空 MQ 数据卷。
7. 启动服务，不需要重新打包或发版：

   ```bash
   bash scripts/server/apps.sh start
   bash scripts/server/apps.sh check
   ```

8. 小程序退出旧登录后重新登录，必要时清除该小程序的本地缓存，避免继续展示旧用户/企业信息。
   依次完成：手机号登录 → 新个人实名 → 新企业创建 → 企业认证 → 确认本地认证成功及角色。

## 怎样才算新的个人及企业测试

清空本地数据库不会删除法大大侧的认证。新用户 ID 只更换本系统对接的用户标识，
不能保证同一法大大账号或手机号可以从原实名人改成另一人。
若测试另一个人，应在法大大认证页面使用该本人账号完成授权与实名；当前实现会预填小程序绑定手机号，但允许在认证页面编辑。
如果法大大页面仍直接显示原实名，应先通过法大大支持的方式处理账号/测试环境，不能把旧实名强行改成新实名。

新企业应使用与旧测试不同的统一社会信用代码；只改企业名称不等于新企业。
本次个人实名人应与企业认证经办人一致；如果测试法人本人流程，由同一本人完成。
不要手工把 `local_status` 改成 `VERIFIED` 或修改 `operator_id` 来跳过经办人匹配。

本次脚本只交付给操作者执行，不代表已对服务器清理。运行前应保留当前故障的诊断结果，以免删掉排查证据。

## 切换正式法大大时的执行顺序

1. 先备份当前 Nacos 的 `tradepass-common.yaml`、`tradepass-identity.yaml`、`tradepass-business.yaml` 以及两库数据。确认 Nacos 中实际库名和本脚本一致。
2. 停止应用和其他写入。按上文预览、清空、再次预览；不重置自增序列或 Snowflake 配置，避免复用旧用户/企业标识。
3. 修改 Nacos 的 `tradepass.fadada`：正式 `app-id`、正式 `app-secret`、`server-url: https://api.fadada.com/api/v5`、`callback-url: https://sqt.org.cn/api/fadada/callback`、`enabled: true`。保留原 YAML 的其他配置，检查 identity/business 配置中是否存在同名覆盖项。
4. 正式法大大应用配置同一回调地址；小程序后台核对正式认证与签署页面实际使用的业务域名。旧沙箱的账户标识、印章标识、签署任务和授权记录不导入正式应用。
5. 核对 Nacos 会员配置里的企业 ID 白名单、黑名单和限定套餐：清空后旧企业 ID 已失效，应按新企业 ID 更新。会员策略从 Nacos 恢复，测试赠送量和付费权益从零开始。
6. 清理本部署的 Redis 登录会话缓存或等待其 TTL 后启动三个应用。SDK 客户端在构造时绑定应用凭据，修改 Nacos 后必须重启 identity/business；同次清理启动会完成重载。
7. 小程序清除旧登录信息，重新登录并依次验收个人认证、企业认证、印章、双边签署及回调。正式签署可能消耗套餐额度，按实际上线验收安排操作。
