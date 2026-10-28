CREATE TABLE fadada_signing_cancellation_intent (
    sign_task_id VARCHAR(128) PRIMARY KEY,
    contract_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    unsigned_only BOOLEAN NOT NULL,
    reason VARCHAR(256) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    owned_actor_ids TEXT NOT NULL DEFAULT ('[]'),
    requested_at DATETIME(6) NOT NULL,
    next_attempt_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    INDEX idx_fadada_cancellation_retry (status, next_attempt_at)
);

CREATE INDEX idx_fadada_unsigned_timeout ON fadada_contract_sign_task (created_at, id);
