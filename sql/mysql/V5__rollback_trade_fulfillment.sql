-- 第二阶段履约功能已撤回；保留 V4 中的 audit_log 供第一阶段操作审计使用。
SET @phase2_contract_id = (
    SELECT id FROM trade_contract
    WHERE client_request_id = 'phase12-20260717-contract'
    LIMIT 1
);
SET @phase2_order_id = (
    SELECT id FROM trade_order
    WHERE client_request_id = 'phase12-20260717-order'
    LIMIT 1
);
SET @phase2_delivery_id = (
    SELECT id FROM delivery_note
    WHERE client_request_id = 'phase12-20260717-delivery'
    LIMIT 1
);

DELETE FROM audit_log
WHERE (biz_type = 'CONTRACT'
    AND biz_id = CAST(@phase2_contract_id AS CHAR) COLLATE utf8mb4_general_ci)
   OR (biz_type = 'ORDER'
    AND biz_id = CAST(@phase2_order_id AS CHAR) COLLATE utf8mb4_general_ci)
   OR (biz_type = 'DELIVERY'
    AND biz_id = CAST(@phase2_delivery_id AS CHAR) COLLATE utf8mb4_general_ci);

DROP TABLE IF EXISTS delivery_receipt_item;
DROP TABLE IF EXISTS delivery_receipt;
DROP TABLE IF EXISTS delivery_item;
DROP TABLE IF EXISTS delivery_note;
DROP TABLE IF EXISTS trade_order_item;
DROP TABLE IF EXISTS contract_item;
DROP TABLE IF EXISTS contract_party_snapshot;

DELETE FROM trade_order
WHERE client_request_id = 'phase12-20260717-order';

DELETE FROM trade_contract
WHERE client_request_id = 'phase12-20260717-contract';

DELETE FROM perm_def
WHERE code IN ('delivery_view', 'delivery_create', 'delivery_receipt');

UPDATE role_def
SET permissions = JSON_REMOVE(
        permissions,
        JSON_UNQUOTE(JSON_SEARCH(permissions, 'one', 'delivery_view'))
    )
WHERE JSON_SEARCH(permissions, 'one', 'delivery_view') IS NOT NULL;

UPDATE role_def
SET permissions = JSON_REMOVE(
        permissions,
        JSON_UNQUOTE(JSON_SEARCH(permissions, 'one', 'delivery_create'))
    )
WHERE JSON_SEARCH(permissions, 'one', 'delivery_create') IS NOT NULL;

UPDATE role_def
SET permissions = JSON_REMOVE(
        permissions,
        JSON_UNQUOTE(JSON_SEARCH(permissions, 'one', 'delivery_receipt'))
    )
WHERE JSON_SEARCH(permissions, 'one', 'delivery_receipt') IS NOT NULL;
