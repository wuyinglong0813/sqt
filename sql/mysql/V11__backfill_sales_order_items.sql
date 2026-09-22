-- V10 之前的销售单已采用固定八列表格，这里补齐结构化明细，便于需方接收并入库。
INSERT INTO business_document_item
    (document_id, issuer_company_id, recipient_company_id, line_no, product_name,
     specification, base_unit, quantity, unit_price, amount, remark)
SELECT document.id,
       document.company_id,
       document.recipient_company_id,
       row_data.line_no,
       LEFT(JSON_UNQUOTE(JSON_EXTRACT(row_data.row_json, '$[1]')), 128),
       LEFT(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(row_data.row_json, '$[2]')), ''), 256),
       LEFT(COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(row_data.row_json, '$[3]')), ''), '件'), 32),
       CAST(JSON_UNQUOTE(JSON_EXTRACT(row_data.row_json, '$[4]')) AS DECIMAL(18,4)),
       COALESCE(CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(row_data.row_json, '$[5]')), '') AS DECIMAL(18,6)), 0),
       COALESCE(CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(row_data.row_json, '$[6]')), '') AS DECIMAL(18,2)), 0),
       LEFT(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(row_data.row_json, '$[7]')), ''), 512)
FROM business_document document
JOIN JSON_TABLE(
        CASE WHEN JSON_VALID(document.content) THEN document.content ELSE '{"rows":[]}' END,
        '$.rows[*]' COLUMNS (
            line_no FOR ORDINALITY,
            row_json JSON PATH '$'
        )
     ) AS row_data
WHERE document.document_type = 'SALES_ORDER'
  AND document.recipient_company_id IS NOT NULL
  AND JSON_VALID(document.content)
  AND JSON_LENGTH(JSON_EXTRACT(document.content, '$.columns')) = 8
  AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(row_data.row_json, '$[1]')), '') <> ''
  AND CAST(JSON_UNQUOTE(JSON_EXTRACT(row_data.row_json, '$[4]')) AS DECIMAL(18,4)) > 0
  AND NOT EXISTS (
      SELECT 1 FROM business_document_item item WHERE item.document_id = document.id
  );
