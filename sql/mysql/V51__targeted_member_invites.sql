-- Never infer verification from a historical phone string. A new server-side
-- WeChat phone authorization records phone_verified_at before targeted joining.
ALTER TABLE sys_user ADD COLUMN phone_verified_at DATETIME NULL AFTER phone;
ALTER TABLE company_invite
    ADD COLUMN target_phone_hash CHAR(64) NULL AFTER relation_role,
    ADD COLUMN target_phone_masked VARCHAR(16) NULL AFTER target_phone_hash,
    ADD COLUMN invited_by BIGINT NULL AFTER target_phone_masked;
-- Existing untargeted member codes remain as historical records but are refused
-- by the service and must be reissued with an explicit recipient. No auto-claim.
