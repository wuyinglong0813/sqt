-- Read-only audit. Do not replay inventory blindly: historical documents may
-- not identify a source warehouse, and manual adjustments may already exist.
-- Sales with buyer inbound but no recorded supplier outbound.
SELECT d.id AS document_id, d.document_no, d.status,
       COALESCE(d.supplier_company_id, CASE WHEN c.direction = 'PURCHASE'
           THEN c.counterparty_company_id ELSE c.company_id END) AS supplier_company_id,
       d.recipient_company_id AS buyer_company_id,
       d.outbound_warehouse_id, ib.id AS inbound_id,
       ib.company_id AS inbound_company_id, ib.warehouse_id AS inbound_warehouse_id,
       ib.created_at AS inbound_at
FROM business_document d
JOIN trade_contract c ON c.id = d.contract_id
JOIN inventory_inbound ib ON ib.source_document_id = d.id AND ib.status = 'COMPLETED'
WHERE d.document_type = 'SALES_ORDER' AND d.status = 'INBOUNDED'
  AND d.deleted_at IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM inventory_transaction t
      WHERE t.biz_type = 'SALES_ORDER_OUTBOUND' AND t.biz_id = ib.id
  )
ORDER BY ib.created_at, d.id;

-- Old returns acknowledged without completing the bilateral inventory transfer.
SELECT d.id AS document_id, d.document_no, d.company_id AS issuer_company_id,
       d.recipient_company_id, d.supplier_company_id, d.buyer_company_id,
       d.outbound_warehouse_id, d.inbound_warehouse_id, d.acknowledged_at
FROM business_document d
WHERE d.document_type = 'RETURN_ORDER' AND d.status = 'ACKNOWLEDGED'
  AND d.deleted_at IS NULL
  AND NOT EXISTS (SELECT 1 FROM inventory_transfer t WHERE t.source_document_id = d.id)
ORDER BY d.acknowledged_at, d.id;

-- Compare both sides of completed transfers; every product quantity must net to zero.
SELECT d.id AS document_id, d.document_no, p.product_name, p.model,
       p.specification, p.base_unit, SUM(t.quantity_delta) AS unmatched_quantity
FROM inventory_transaction t
JOIN inventory_product p ON p.id = t.product_id AND p.company_id = t.company_id
JOIN inventory_transfer transfer ON transfer.id = t.biz_id
JOIN business_document d ON d.id = transfer.source_document_id
WHERE t.biz_type IN ('RETURN_ORDER_OUTBOUND', 'RETURN_ORDER_INBOUND')
  AND transfer.status = 'COMPLETED'
GROUP BY d.id, d.document_no, p.product_name, p.model, p.specification, p.base_unit
HAVING SUM(t.quantity_delta) <> 0
ORDER BY d.id, p.product_name, p.model, p.specification;
