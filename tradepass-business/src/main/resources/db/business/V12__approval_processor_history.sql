-- Mirror only recorded counterparty decisions. Negative IDs reserve a deterministic
-- one-to-one key for each positive source event, so repeated execution cannot grow history.
-- Historical records are marked read to avoid a burst of stale unread notifications.
INSERT INTO approval_result_notification
    (id, recipient_company_id, source_company_id, result_type, source_id, contract_id,
     result_status, title, detail, rejected_reason, read_at, created_at)
SELECT -original.id, original.source_company_id, original.recipient_company_id,
       original.result_type, original.source_id, original.contract_id, original.result_status,
       CASE WHEN original.result_status = 'REJECTED' THEN '我方已驳回审批'
            WHEN original.result_status = 'INBOUNDED' THEN '我方已同意并入库'
            ELSE '我方已同意审批' END,
       CONCAT('我方', SUBSTRING(original.detail, 3)), original.rejected_reason,
       original.created_at, original.created_at
FROM approval_result_notification original
WHERE original.id > 0
  AND original.source_company_id <> original.recipient_company_id
  AND original.result_type IN ('SALES_ORDER', 'RETURN_ORDER', 'PAYMENT_VOUCHER', 'INVOICE', 'BILATERAL_ACTION')
  AND original.result_status IN ('APPROVED', 'INBOUNDED', 'REJECTED')
  AND original.detail LIKE '对方%'
  AND NOT EXISTS (SELECT 1 FROM approval_result_notification mirrored WHERE mirrored.id = -original.id)
  AND NOT EXISTS (
      SELECT 1 FROM approval_result_notification existing
      WHERE existing.recipient_company_id = original.source_company_id
        AND existing.source_company_id = original.recipient_company_id
        AND existing.result_type = original.result_type
        AND existing.source_id = original.source_id
        AND existing.result_status = original.result_status
        AND existing.created_at = original.created_at
        AND existing.detail LIKE '我方%'
  );
