-- Retire pending recovery requests from the former provider abolish workflow.
UPDATE bilateral_action_request
SET status = 'CANCELLED', cancelled_at = CURRENT_TIMESTAMP,
    decision_reason = '合同作废流程已调整，历史恢复申请已关闭'
WHERE biz_type = 'CONTRACT' AND action_type = 'RESUME' AND status = 'PENDING';

-- Finish already approved local voids without creating or completing provider tasks.
-- Preserve any other pending action for bilateral resolution; retain provider evidence.
UPDATE trade_contract contract
JOIN bilateral_action_request request ON request.contract_id = contract.id
    AND request.biz_type = 'CONTRACT' AND request.biz_id = contract.id
    AND request.action_type = 'VOID' AND request.status = 'APPROVED'
LEFT JOIN bilateral_action_request pending ON pending.contract_id = contract.id AND pending.status = 'PENDING'
SET contract.status = 'VOIDED', contract.updated_at = CURRENT_TIMESTAMP
WHERE contract.status = 'ACTIVE' AND pending.id IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM business_document document
      WHERE document.contract_id = contract.id AND document.deleted_at IS NULL
        AND document.status IN ('DRAFT', 'ISSUED', 'REJECTED', 'ACKNOWLEDGED', 'INBOUNDED')
  )
  AND NOT EXISTS (
      SELECT 1 FROM contract_attachment attachment
      WHERE attachment.contract_id = contract.id AND attachment.deleted_at IS NULL
        AND attachment.category IN ('PAYMENT_VOUCHER', 'INVOICE')
        AND attachment.status IN ('PENDING_CONFIRMATION', 'REJECTED', 'APPROVED')
  );
