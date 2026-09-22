CREATE TABLE member_removal_notice (
    id BIGINT NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    company_id BIGINT NOT NULL,
    company_name VARCHAR(200) NOT NULL,
    removed_at DATETIME NOT NULL,
    acknowledged_at DATETIME NULL,
    KEY idx_removal_notice_user_company (user_id, company_id, id)
);
