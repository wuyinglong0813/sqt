UPDATE approval_result_notification notification
JOIN trade_contract contract ON contract.id = notification.source_id
JOIN company initiator ON initiator.id = contract.company_id
SET notification.title = CONCAT(
        '已拒绝', initiator.name, '的',
        CASE
            WHEN contract.name LIKE '%合同' THEN contract.name
            ELSE CONCAT(contract.name, '合同')
        END
    ),
    notification.detail = CONCAT('我方已拒绝合同 ', contract.contract_no),
    notification.contract_id = NULL
WHERE notification.result_type = 'CONTRACT'
  AND notification.result_status = 'REJECTED'
  AND notification.recipient_company_id = contract.counterparty_company_id
  AND notification.source_company_id = contract.company_id;
