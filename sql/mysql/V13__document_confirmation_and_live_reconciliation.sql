ALTER TABLE business_document
    ADD COLUMN rejected_reason VARCHAR(500) NULL AFTER acknowledged_at;

ALTER TABLE contract_attachment
    ADD COLUMN recipient_company_id BIGINT NULL AFTER uploader_company_id,
    ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'APPROVED' AFTER category,
    ADD COLUMN invoice_no VARCHAR(128) NULL AFTER voucher_amount,
    ADD COLUMN invoice_date DATE NULL AFTER invoice_no,
    ADD COLUMN invoice_amount DECIMAL(18,2) NULL AFTER invoice_date,
    ADD COLUMN confirmed_by BIGINT NULL AFTER created_by,
    ADD COLUMN confirmed_at DATETIME NULL AFTER confirmed_by,
    ADD COLUMN rejected_reason VARCHAR(500) NULL AFTER confirmed_at,
    ADD INDEX idx_attachment_recipient_status (recipient_company_id, status, category, created_at);

UPDATE contract_attachment attachment
JOIN trade_contract contract ON contract.id = attachment.contract_id
SET attachment.recipient_company_id = CASE
        WHEN attachment.uploader_company_id = contract.company_id
            THEN contract.counterparty_company_id
        ELSE contract.company_id
    END
WHERE attachment.recipient_company_id IS NULL;

-- 历史财务附件在旧版本中没有经过对方确认，发票也没有结构化金额。
-- 保留双方查看能力，但不直接计入自动对账；重新上传并确认后再纳入。
UPDATE contract_attachment
SET status = 'LEGACY'
WHERE category IN ('PAYMENT_VOUCHER', 'INVOICE');

CREATE TABLE reconciliation_entry (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    company_a_id BIGINT NOT NULL,
    company_b_id BIGINT NOT NULL,
    contract_id BIGINT NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_id BIGINT NOT NULL,
    business_date DATE NOT NULL,
    document_no VARCHAR(255) NOT NULL,
    amount DECIMAL(18,2) NOT NULL,
    supplier_company_id BIGINT NOT NULL,
    buyer_company_id BIGINT NOT NULL,
    issuer_company_id BIGINT NOT NULL,
    approved_by BIGINT NOT NULL,
    approved_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_reconciliation_source (source_type, source_id),
    INDEX idx_reconciliation_pair (company_a_id, company_b_id, business_date, id),
    INDEX idx_reconciliation_contract (contract_id, source_type)
);

-- 已经由需方接收或入库的历史销售单可以安全视为双方已确认。
INSERT IGNORE INTO reconciliation_entry
    (company_a_id, company_b_id, contract_id, source_type, source_id, business_date,
     document_no, amount, supplier_company_id, buyer_company_id, issuer_company_id,
     approved_by, approved_at)
SELECT LEAST(document.company_id, document.recipient_company_id),
       GREATEST(document.company_id, document.recipient_company_id),
       document.contract_id,
       'SALES_ORDER',
       document.id,
       DATE(document.created_at),
       document.document_no,
       COALESCE(SUM(item.amount), 0),
       document.company_id,
       document.recipient_company_id,
       document.company_id,
       COALESCE(document.acknowledged_by, document.created_by, 0),
       COALESCE(document.acknowledged_at, document.created_at)
FROM business_document document
LEFT JOIN business_document_item item ON item.document_id = document.id
WHERE document.document_type = 'SALES_ORDER'
  AND document.status IN ('ACKNOWLEDGED', 'INBOUNDED')
  AND document.recipient_company_id IS NOT NULL
GROUP BY document.id, document.company_id, document.recipient_company_id,
         document.contract_id, document.created_at, document.document_no,
         document.acknowledged_by, document.created_by, document.acknowledged_at;
