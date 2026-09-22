ALTER TABLE company_member
    ADD COLUMN role_codes JSON NULL COMMENT 'Assigned role codes; NULL uses legacy role_code' AFTER role_code;
