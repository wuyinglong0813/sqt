INSERT INTO business_document_template
    (company_id, document_type, name, content, created_by)
SELECT company.id, 'RETURN_ORDER', '标准退货单模板',
       '{"columns":["序号","品名","规格","单位","数量","单价","金额","退货原因"],"blankRows":8}',
       COALESCE((SELECT company_member.user_id
                 FROM company_member
                 WHERE company_member.company_id = company.id
                 ORDER BY company_member.id
                 LIMIT 1), 1)
FROM company
WHERE NOT EXISTS (
    SELECT 1
    FROM business_document_template template
    WHERE template.company_id = company.id
      AND template.document_type = 'RETURN_ORDER'
);
