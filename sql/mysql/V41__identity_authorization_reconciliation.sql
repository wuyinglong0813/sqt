ALTER TABLE fadada_user_identity
    ADD COLUMN authorization_event_at DATETIME(3) NULL,
    ADD COLUMN authorization_event_type VARCHAR(40) NULL,
    ADD COLUMN authorization_revoked_at DATETIME(3) NULL,
    ADD COLUMN last_reconciled_at DATETIME NULL,
    ADD INDEX idx_user_identity_reconcile (last_reconciled_at, local_status);

ALTER TABLE fadada_corp_identity
    ADD COLUMN authorization_user_id BIGINT NULL,
    ADD COLUMN authorization_submitted_at DATETIME(3) NULL,
    ADD COLUMN authorization_confirmed_at DATETIME(3) NULL,
    ADD COLUMN authorization_event_at DATETIME(3) NULL,
    ADD COLUMN authorization_event_type VARCHAR(40) NULL,
    ADD COLUMN authorization_revoked_at DATETIME(3) NULL,
    ADD COLUMN last_reconciled_at DATETIME NULL,
    ADD INDEX idx_corp_identity_reconcile (last_reconciled_at, local_status);

-- Preserve original certification applicant/provider_request_id as historical facts.
UPDATE fadada_corp_identity
SET authorization_user_id = applicant_user_id,
    authorization_submitted_at = submitted_at,
    authorization_confirmed_at = CASE WHEN local_status = 'VERIFIED' THEN COALESCE(last_sync_at, verified_at) ELSE NULL END;
