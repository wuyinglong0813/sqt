ALTER TABLE fadada_contract_sign_task
    ADD COLUMN contract_snapshot LONGTEXT NULL AFTER source_sha256;

ALTER TABLE fadada_callback_event
    ADD COLUMN retry_payload TEXT NULL AFTER payload_sha256,
    ADD COLUMN attempt_count INT NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at DATETIME NULL,
    ADD COLUMN processing_token VARCHAR(64) NULL,
    ADD COLUMN lease_until DATETIME NULL,
    ADD INDEX idx_callback_retry (status, next_attempt_at, lease_until);
