-- Freeze the provider billing initiator; NULL keeps historical contract-company initiation semantics.
-- Never backfill existing reservations/tasks with the platform's current billing company.
ALTER TABLE fadada_contract_sign_task
    ADD COLUMN provider_initiator_open_corp_id VARCHAR(64) NULL AFTER contract_snapshot;

ALTER TABLE membership_signing_usage
    ADD COLUMN provider_initiator_open_corp_id VARCHAR(64) NULL AFTER source_sha256;
