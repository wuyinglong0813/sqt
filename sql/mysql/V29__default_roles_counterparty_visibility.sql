-- 默认业务角色统一勾选合作企业查看，仅追加该权限，保留其余配置。
-- 法人已有 all；访客和待认证身份不属于可分配的默认业务角色。
UPDATE role_def
SET permissions = JSON_ARRAY_APPEND(COALESCE(permissions, JSON_ARRAY()), '$', 'counterparty_view')
WHERE system_role = 1
  AND code IN ('ADMIN', 'SALES', 'PURCHASER', 'FINANCE')
  AND NOT JSON_CONTAINS(COALESCE(permissions, JSON_ARRAY()), JSON_QUOTE('counterparty_view'));
