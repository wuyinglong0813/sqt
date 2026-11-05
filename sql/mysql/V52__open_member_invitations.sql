-- Explicitly mark new shareable invitations. Historical untargeted invitations
-- remain LEGACY and are never made redeemable by this migration.
ALTER TABLE company_invite
    ADD COLUMN invitation_mode VARCHAR(16) NOT NULL DEFAULT 'LEGACY' AFTER type;
ALTER TABLE company_member
    ADD COLUMN invite_id BIGINT NULL AFTER user_id,
    ADD COLUMN invited_by BIGINT NULL AFTER invite_id,
    ADD INDEX idx_member_application_user (user_id, status);
