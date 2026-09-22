ALTER TABLE fadada_corp_identity
    ADD COLUMN operator_type VARCHAR(32) NULL COMMENT '认证服务返回的经办人类型',
    ADD COLUMN operator_id VARCHAR(128) NULL COMMENT '认证服务返回的经办人标识';

-- Historical roles require provider evidence; do not infer or bulk change them from names.
UPDATE role_def SET name = '企业认证待审核'
WHERE code = 'LEGAL_CANDIDATE' AND system_role = TRUE;
