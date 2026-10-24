ALTER TABLE trade_contract
    ADD COLUMN counterparty_hidden TINYINT(1) NOT NULL DEFAULT 0 AFTER initiator_hidden;
