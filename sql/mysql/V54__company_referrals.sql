ALTER TABLE company_onboarding_attempt ADD COLUMN referral_invite_id BIGINT NULL;

CREATE TABLE company_referral (
    invite_id BIGINT NOT NULL PRIMARY KEY,
    inviter_company_id BIGINT NOT NULL,
    inviter_credit_code VARCHAR(64) NOT NULL,
    invited_company_id BIGINT NOT NULL,
    invited_credit_code VARCHAR(64) NOT NULL,
    confirmed_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_company_referral_invited_credit (invited_credit_code),
    INDEX idx_company_referral_inviter (inviter_credit_code, confirmed_at)
);
