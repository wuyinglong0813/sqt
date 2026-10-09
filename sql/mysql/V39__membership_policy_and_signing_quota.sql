-- Membership times in these tables are Beijing local time. No cross-domain foreign keys.
CREATE TABLE membership_policy_state (
    id INT PRIMARY KEY,
    revision BIGINT NOT NULL DEFAULT 0,
    content_hash CHAR(64) NOT NULL DEFAULT '',
    content MEDIUMTEXT NOT NULL,
    updated_at DATETIME(6) NOT NULL
);
INSERT INTO membership_policy_state(id, content, updated_at) VALUES (1, '', CURRENT_TIMESTAMP(6));

CREATE TABLE membership_policy_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    content_hash CHAR(64) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    source VARCHAR(32) NOT NULL,
    applied_at DATETIME(6) NOT NULL
);

CREATE TABLE membership_quota_pool (
    scope_key VARCHAR(80) NOT NULL,
    subject_key VARCHAR(32) NOT NULL,
    company_id BIGINT NOT NULL,
    total_amount BIGINT NOT NULL DEFAULT 0,
    consumed_amount BIGINT NOT NULL DEFAULT 0,
    reserved_amount BIGINT NOT NULL DEFAULT 0,
    expires_at DATETIME(6) NULL,
    PRIMARY KEY(scope_key, subject_key),
    INDEX idx_membership_pool_subject(subject_key, expires_at)
);

CREATE TABLE membership_trial_budget (
    activity_id VARCHAR(48) PRIMARY KEY,
    allocated_amount BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE membership_signing_usage (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    contract_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    company_id BIGINT NOT NULL,
    subject_key VARCHAR(32) NOT NULL,
    scope_key VARCHAR(80) NOT NULL,
    source VARCHAR(24) NOT NULL,
    status VARCHAR(24) NOT NULL,
    policy_revision BIGINT NOT NULL,
    operator_id BIGINT NOT NULL,
    contract_snapshot MEDIUMTEXT NOT NULL,
    source_sha256 CHAR(64) NOT NULL,
    sign_task_id VARCHAR(128) NULL,
    source_file_id VARCHAR(128) NULL,
    doc_id VARCHAR(128) NULL,
    created_at DATETIME(6) NOT NULL,
    consumed_at DATETIME(6) NULL,
    last_error VARCHAR(255) NULL,
    UNIQUE KEY uk_membership_contract_version(contract_id, version_no),
    UNIQUE KEY uk_membership_provider_task(sign_task_id),
    INDEX idx_membership_usage_company(company_id, id)
);

-- Reserved for confirmed paid membership/credits; no payment is fabricated by the trial module.
CREATE TABLE membership_paid_vip (
    subject_key VARCHAR(32) PRIMARY KEY,
    company_id BIGINT NOT NULL,
    expires_at DATETIME(6) NOT NULL
);
