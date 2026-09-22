ALTER TABLE trade_contract
    ADD COLUMN initiator_hidden TINYINT(1) NOT NULL DEFAULT 0 AFTER status;

UPDATE trade_contract
SET status = 'REJECTED', initiator_hidden = 1
WHERE status = 'DELETED';
