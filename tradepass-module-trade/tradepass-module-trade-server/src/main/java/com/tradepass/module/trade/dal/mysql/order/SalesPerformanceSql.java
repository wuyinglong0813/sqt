package com.tradepass.module.trade.dal.mysql.order;

/** Sales figures are sourced from confirmed documents, including fee rows exactly once. */
public final class SalesPerformanceSql {
    private SalesPerformanceSql() {}
    public static final String AMOUNT = "COALESCE(items.amount, 0)";
    public static final String DATE = """
        COALESCE(CASE WHEN JSON_VALID(d.content) THEN
            STR_TO_DATE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(d.content, '$.date')), ''), '%Y-%m-%d') END,
            DATE(d.created_at))
        """;
    public static final String FROM = """
        FROM business_document d
        LEFT JOIN (SELECT document_id, SUM(amount) AS amount FROM business_document_item GROUP BY document_id)
            items ON items.document_id = d.id
        WHERE d.status IN ('ACKNOWLEDGED', 'INBOUNDED') AND d.deleted_at IS NULL
        """;
    public static final String DIRECTION = """
        AND ((#{direction} = 'SALE' AND d.supplier_company_id = #{companyId})
          OR (#{direction} = 'PURCHASE' AND d.buyer_company_id = #{companyId}))
        """;
    public static final String PERIOD = " AND (#{period} = 'total'"
            + " OR (#{period} = 'year' AND YEAR(" + DATE + ") = YEAR(CURDATE()))"
            + " OR (#{period} = 'month' AND DATE_FORMAT(" + DATE + ", '%Y-%m') = DATE_FORMAT(CURDATE(), '%Y-%m'))"
            + " OR (#{period} = 'last12' AND " + DATE + " >= DATE_FORMAT(DATE_SUB(CURDATE(), INTERVAL 11 MONTH), '%Y-%m-01')))";
}
