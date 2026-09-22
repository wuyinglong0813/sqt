ALTER TABLE fadada_corp_identity
    ADD COLUMN provider_request_id VARCHAR(128) NULL COMMENT '当前经办人对应的本地认证申请',
    ADD COLUMN seal_sync_warning VARCHAR(512) NULL COMMENT '独立于企业认证的印章同步提示';
