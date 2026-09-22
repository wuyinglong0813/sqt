CREATE TABLE contract_archive (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    contract_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    storage_provider VARCHAR(32) NOT NULL,
    storage_bucket VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    object_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    object_version_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    etag VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    original_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    file_size BIGINT NOT NULL,
    sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    encryption_algorithm VARCHAR(32) NOT NULL,
    archived_by BIGINT NOT NULL,
    archived_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_contract_archive_version (contract_id, version_no),
    UNIQUE KEY uk_contract_archive_object (storage_bucket, object_key),
    INDEX idx_contract_archive_time (archived_at)
);

ALTER TABLE contract_attachment
    MODIFY COLUMN file_data LONGBLOB NULL,
    ADD COLUMN storage_provider VARCHAR(32) NULL AFTER sha256,
    ADD COLUMN storage_bucket VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER storage_provider,
    ADD COLUMN object_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER storage_bucket,
    ADD COLUMN object_version_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER object_key,
    ADD COLUMN etag VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER object_version_id,
    ADD COLUMN encryption_algorithm VARCHAR(32) NULL AFTER etag,
    ADD UNIQUE KEY uk_attachment_object (storage_bucket, object_key);

ALTER TABLE logistics_document
    MODIFY COLUMN image_data LONGBLOB NULL,
    ADD COLUMN sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER image_data,
    ADD COLUMN storage_provider VARCHAR(32) NULL AFTER sha256,
    ADD COLUMN storage_bucket VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER storage_provider,
    ADD COLUMN object_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER storage_bucket,
    ADD COLUMN object_version_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER object_key,
    ADD COLUMN etag VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER object_version_id,
    ADD COLUMN encryption_algorithm VARCHAR(32) NULL AFTER etag,
    ADD UNIQUE KEY uk_logistics_object (storage_bucket, object_key);

ALTER TABLE reconciliation_statement
    MODIFY COLUMN file_data LONGBLOB NULL,
    ADD COLUMN storage_provider VARCHAR(32) NULL AFTER sha256,
    ADD COLUMN storage_bucket VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER storage_provider,
    ADD COLUMN object_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER storage_bucket,
    ADD COLUMN object_version_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER object_key,
    ADD COLUMN etag VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER object_version_id,
    ADD COLUMN encryption_algorithm VARCHAR(32) NULL AFTER etag,
    ADD UNIQUE KEY uk_statement_object (storage_bucket, object_key);
