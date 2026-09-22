UPDATE role_def
SET name = '法人', system_role = 1, permissions = JSON_ARRAY('all')
WHERE code = 'LEGAL';

UPDATE role_def
SET name = '管理员', system_role = 1,
    permissions = JSON_ARRAY('member_manage', 'auth_manage', 'company_manage', 'seal_manage', 'contract_template')
WHERE code = 'ADMIN';

UPDATE role_def
SET name = '销售员', system_role = 1,
    permissions = JSON_ARRAY('supplier_view', 'counterparty_manage', 'order_view', 'order_create',
                             'contract_sign', 'contract_view', 'reconciliation')
WHERE code = 'SALES';

UPDATE role_def
SET name = '采购员', system_role = 1,
    permissions = JSON_ARRAY('buyer_view', 'order_create', 'contract_view', 'order_view',
                             'contract_sign', 'reconciliation')
WHERE code = 'PURCHASER';

UPDATE role_def
SET name = '财务', system_role = 1,
    permissions = JSON_ARRAY('invoice_view', 'reconciliation')
WHERE code = 'FINANCE';
