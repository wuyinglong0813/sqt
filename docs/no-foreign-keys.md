# 数据库不使用外键约束

所有应用库升级后不保留物理外键。关联字段、普通索引、唯一索引和业务代码的权限/身份/关联校验继续保留。
删除数据库外键不会绕过企业认证经办人匹配，也不会重置法大大侧实名。

## 已有服务器立即移除

停止应用写入并备份数据库结构，核对库名后在 MySQL 客户端整份执行
[`scripts/remove-all-foreign-keys.sql`](../scripts/remove-all-foreign-keys.sql)。
默认只处理当前三进程部署的 `tradepass_staging_identity` 和 `tradepass_staging_business` 两库。
每库输出 `remaining_foreign_keys = 0` 即完成。只移除约束，不删除记录、表或索引，不改 Flyway 历史，可重复执行。
执行账号需要对应数据库的 ALTER、CREATE ROUTINE、ALTER ROUTINE 和 EXECUTE 权限；项目初始化的应用账号已授予本库 ALL PRIVILEGES。
DDL 不支持事务回滚；若中途报错，处理原因后再次执行剩余约束移除，不要误称整个操作已回滚。

随后可执行 [`scripts/reset-test-data.sql`](../scripts/reset-test-data.sql) 清空测试业务数据。
这是另一个独立操作，默认仍为 PREVIEW，去外键脚本本身不会清空数据库。

## 后续部署和新建库

- 当前三进程环境：发布 identity、business，启动时分别执行 owned V2；gateway 和小程序无需因去外键而发版。
- 原四库环境：identity、contract、trade、settlement 各自包含 V2。
- 旧单库迁移目录：新增 V37。
- 新库也执行完整迁移链，启动完成后同样没有外键。

已经上线的历史 V1–V36 及 owned V1 保持不变，避免 Flyway checksum mismatch。
因此历史 SQL 里仍可看到创建外键的旧语句，紧随其后的 V37 / owned V2 会全部移除。
不要手改历史文件、删除 Flyway 历史或用 repair 隐瞒修改。
后续新迁移不得新增外键；CI 检查全部迁移布局和生成结果。

[`scripts/generate-no-foreign-keys.py`](../scripts/generate-no-foreign-keys.py) 统一生成六份增量迁移及服务器脚本。
原 [`scripts/generate-owned-schema.py`](../scripts/generate-owned-schema.py) 仅重现不可变的 V1 基线，不吸收 V37 改写旧校验和。
离线拆库/合库工具不再关闭外键检查；合库的项目与合同等数据一致性检查由迁移代码执行。
