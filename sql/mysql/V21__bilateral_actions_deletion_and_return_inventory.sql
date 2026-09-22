-- 合同履约资料删除、双方作废/结束，以及退货库存转移。

ALTER TABLE contract_attachment
    ADD COLUMN deleted_by BIGINT NULL AFTER rejected_reason,
    ADD COLUMN deleted_at DATETIME NULL AFTER deleted_by,
    ADD INDEX idx_contract_attachment_deleted (contract_id, category, deleted_at);

ALTER TABLE logistics_document
    ADD COLUMN deleted_by BIGINT NULL AFTER created_by,
    ADD COLUMN deleted_at DATETIME NULL AFTER deleted_by,
    ADD INDEX idx_logistics_document_deleted (contract_id, deleted_at);

ALTER TABLE business_document
    ADD COLUMN outbound_warehouse_id BIGINT NULL AFTER rejected_reason,
    ADD COLUMN inbound_warehouse_id BIGINT NULL AFTER outbound_warehouse_id;

CREATE TABLE bilateral_action_request (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    contract_id BIGINT NOT NULL,
    biz_type VARCHAR(32) NOT NULL,
    biz_id BIGINT NOT NULL,
    action_type VARCHAR(16) NOT NULL,
    requester_company_id BIGINT NOT NULL,
    requester_user_id BIGINT NOT NULL,
    approver_company_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    reason VARCHAR(500) NOT NULL,
    risk_confirmed TINYINT(1) NOT NULL DEFAULT 0,
    decision_reason VARCHAR(500) NULL,
    decided_by BIGINT NULL,
    decided_at DATETIME NULL,
    cancelled_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_bilateral_action_approver (approver_company_id, status, created_at),
    INDEX idx_bilateral_action_biz (biz_type, biz_id, created_at),
    INDEX idx_bilateral_action_contract (contract_id, status, created_at)
);

-- MySQL 不支持带条件的唯一索引；active_key 仅在待处理时有值，处理后清空。
ALTER TABLE bilateral_action_request
    ADD COLUMN active_key VARCHAR(96)
        GENERATED ALWAYS AS (
            CASE WHEN status = 'PENDING'
                THEN CONCAT(biz_type, ':', biz_id, ':', action_type)
                ELSE NULL END
        ) STORED,
    ADD UNIQUE KEY uk_bilateral_action_active (active_key);

CREATE TABLE inventory_transfer (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    source_document_id BIGINT NOT NULL,
    outbound_company_id BIGINT NOT NULL,
    outbound_warehouse_id BIGINT NOT NULL,
    inbound_company_id BIGINT NOT NULL,
    inbound_warehouse_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'COMPLETED',
    reversed_by_action_id BIGINT NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reversed_at DATETIME NULL,
    UNIQUE KEY uk_inventory_transfer_document (source_document_id),
    INDEX idx_inventory_transfer_outbound (outbound_company_id, outbound_warehouse_id, created_at),
    INDEX idx_inventory_transfer_inbound (inbound_company_id, inbound_warehouse_id, created_at)
);

ALTER TABLE reconciliation_entry
    ADD COLUMN reversal_of_id BIGINT NULL AFTER approved_at,
    ADD COLUMN action_request_id BIGINT NULL AFTER reversal_of_id,
    ADD UNIQUE KEY uk_reconciliation_reversal (reversal_of_id),
    ADD INDEX idx_reconciliation_action (action_request_id);
