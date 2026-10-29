-- Verification must come from a fresh server-side WeChat phone authorization.
ALTER TABLE sys_user ADD COLUMN phone_verified_at DATETIME NULL AFTER phone;
ALTER TABLE company_invite
    ADD COLUMN target_phone_hash CHAR(64) NULL AFTER relation_role,
    ADD COLUMN target_phone_masked VARCHAR(16) NULL AFTER target_phone_hash,
    ADD COLUMN invited_by BIGINT NULL AFTER target_phone_masked;
