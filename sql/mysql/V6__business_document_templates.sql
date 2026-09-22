CREATE TABLE IF NOT EXISTS business_document_template (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    company_id BIGINT NOT NULL,
    document_type VARCHAR(32) NOT NULL,
    name VARCHAR(256) NOT NULL,
    content TEXT,
    source_file_name VARCHAR(256),
    created_by BIGINT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_document_template_company_type (company_id, document_type)
);

CREATE TABLE IF NOT EXISTS business_document (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    company_id BIGINT NOT NULL,
    contract_id BIGINT NOT NULL,
    document_type VARCHAR(32) NOT NULL,
    document_no VARCHAR(64) NOT NULL,
    template_id BIGINT NOT NULL,
    template_name VARCHAR(256) NOT NULL,
    content LONGTEXT,
    created_by BIGINT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_business_document_no (document_no),
    INDEX idx_business_document_contract_type (company_id, contract_id, document_type)
);
