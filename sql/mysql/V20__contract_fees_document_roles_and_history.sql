-- 单据创建方/确认方与合同买卖角色解耦，支持双方发起退货单。
ALTER TABLE business_document
    ADD COLUMN supplier_company_id BIGINT NULL AFTER recipient_company_id,
    ADD COLUMN buyer_company_id BIGINT NULL AFTER supplier_company_id,
    ADD COLUMN deleted_by BIGINT NULL AFTER rejected_reason,
    ADD COLUMN deleted_at DATETIME NULL AFTER deleted_by,
    ADD INDEX idx_business_document_roles (supplier_company_id, buyer_company_id, document_type, status),
    ADD INDEX idx_business_document_deleted (deleted_at, company_id, status);

UPDATE business_document document
JOIN trade_contract contract ON contract.id = document.contract_id
SET document.supplier_company_id = CASE
        WHEN UPPER(COALESCE(contract.direction, 'SALE')) = 'PURCHASE'
            THEN contract.counterparty_company_id
        ELSE contract.company_id
    END,
    document.buyer_company_id = CASE
        WHEN UPPER(COALESCE(contract.direction, 'SALE')) = 'PURCHASE'
            THEN contract.company_id
        ELSE contract.counterparty_company_id
    END
WHERE document.supplier_company_id IS NULL
   OR document.buyer_company_id IS NULL;

-- 费用明细参与金额和对账，但不参与库存商品处理。
ALTER TABLE business_document_item
    ADD COLUMN line_type VARCHAR(16) NOT NULL DEFAULT 'PRODUCT' AFTER line_no,
    ADD INDEX idx_document_item_type (document_id, line_type, line_no);

-- 审批结果改为不可覆盖的事件历史；同一合同每次撤回/重新处理都保留记录。
ALTER TABLE approval_result_notification
    DROP INDEX uk_approval_result_source;

