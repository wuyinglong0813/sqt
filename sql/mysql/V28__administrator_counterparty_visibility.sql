-- 合作关系属于企业；查看已有绑定不授予邀请、签约或财务操作权限。
INSERT IGNORE INTO perm_def (code, label, sort_order)
VALUES ('counterparty_view', '合作企业查看', 19);

-- 只修复仍采用原始默认权限的管理员角色，保留企业已经定制的权限配置。
UPDATE role_def
SET permissions = JSON_ARRAY_APPEND(permissions, '$', 'counterparty_view')
WHERE code = 'ADMIN'
  AND system_role = 1
  AND JSON_LENGTH(permissions) = 5
  AND JSON_CONTAINS(permissions,
      JSON_ARRAY('member_manage', 'auth_manage', 'company_manage', 'seal_manage', 'contract_template'));
