package com.tradepass.module.trade.dal.mysql.order;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tradepass.module.trade.dal.dataobject.order.TradeOrderDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface TradeOrderMapper extends BaseMapper<TradeOrderDO> {
    @Select("""
        SELECT CASE WHEN #{direction} = 'SALE' THEN d.buyer_company_id ELSE d.supplier_company_id END AS counterpartyCompanyId,
            MAX(CASE WHEN JSON_VALID(d.content) THEN JSON_UNQUOTE(JSON_EXTRACT(d.content,
                CASE WHEN #{direction} = 'SALE' THEN '$.counterpartyName' ELSE '$.companyName' END)) END) AS counterpartyName,
        """ + "SUM(" + SalesPerformanceSql.AMOUNT + ") AS totalAmount, COUNT(*) AS orderCount "
            + SalesPerformanceSql.FROM + SalesPerformanceSql.DIRECTION + SalesPerformanceSql.PERIOD + """
        AND d.document_type = 'SALES_ORDER'
        GROUP BY counterpartyCompanyId
        ORDER BY totalAmount DESC
        """)
    List<Map<String, Object>> selectRanking(@Param("companyId") Long companyId,
                                            @Param("direction") String direction,
                                            @Param("period") String period);

    @Select("SELECT d.contract_id AS contractId, "
            + "COALESCE(SUM(CASE WHEN d.document_type = 'SALES_ORDER' THEN " + SalesPerformanceSql.AMOUNT + " ELSE 0 END), 0) AS salesAmount, "
            + "COALESCE(SUM(CASE WHEN d.document_type = 'RETURN_ORDER' THEN ABS(" + SalesPerformanceSql.AMOUNT + ") ELSE 0 END), 0) AS returnAmount, "
            + "SUM(CASE WHEN d.document_type = 'SALES_ORDER' THEN 1 ELSE 0 END) AS salesOrderCount "
            + SalesPerformanceSql.FROM + " AND (d.supplier_company_id = #{companyId} OR d.buyer_company_id = #{companyId}) "
            + "AND d.document_type IN ('SALES_ORDER', 'RETURN_ORDER') GROUP BY d.contract_id")
    List<Map<String, Object>> selectContractSales(@Param("companyId") Long companyId);

    @Select("SELECT COALESCE(SUM(CASE WHEN d.document_type = 'RETURN_ORDER' THEN ABS(" + SalesPerformanceSql.AMOUNT
            + ") ELSE 0 END), 0) AS returnAmount " + SalesPerformanceSql.FROM
            + SalesPerformanceSql.DIRECTION + SalesPerformanceSql.PERIOD
            + " AND d.document_type IN ('SALES_ORDER', 'RETURN_ORDER')")
    Map<String, Object> selectSalesTotals(@Param("companyId") Long companyId,
                                        @Param("direction") String direction, @Param("period") String period);

    @Select("""
        SELECT COUNT(*) AS total, COALESCE(SUM(amount), 0) AS amount
        FROM trade_order
        WHERE company_id = #{companyId}
          AND (#{counterpartyName} IS NULL OR #{counterpartyName} = '' OR counterparty_name = #{counterpartyName})
          AND (#{direction} IS NULL OR #{direction} = '' OR direction = #{direction})
        """)
    Map<String, Object> selectOrderSummary(@Param("companyId") Long companyId,
                                           @Param("counterpartyName") String counterpartyName,
                                           @Param("direction") String direction);

    @Select("SELECT DATE_FORMAT(" + SalesPerformanceSql.DATE + ", '%Y-%m') AS period, COALESCE(SUM(" + SalesPerformanceSql.AMOUNT + "), 0) AS amount "
            + SalesPerformanceSql.FROM + SalesPerformanceSql.DIRECTION
            + " AND d.document_type = 'SALES_ORDER' AND " + SalesPerformanceSql.DATE
            + " >= DATE_FORMAT(DATE_SUB(CURDATE(), INTERVAL 11 MONTH), '%Y-%m-01') AND " + SalesPerformanceSql.DATE
            + " < DATE_FORMAT(DATE_ADD(CURDATE(), INTERVAL 1 MONTH), '%Y-%m-01')"
            + " AND ((#{counterpartyCompanyId} IS NOT NULL AND CASE WHEN #{direction} = 'SALE' THEN d.buyer_company_id ELSE d.supplier_company_id END = #{counterpartyCompanyId})"
            + " OR (#{counterpartyCompanyId} IS NULL AND CASE WHEN JSON_VALID(d.content) THEN JSON_UNQUOTE(JSON_EXTRACT(d.content,"
            + " CASE WHEN #{direction} = 'SALE' THEN '$.counterpartyName' ELSE '$.companyName' END)) END = #{counterpartyName}))"
            + " GROUP BY period ORDER BY period")
    List<Map<String, Object>> selectMonthlyOrderSummary(@Param("companyId") Long companyId,
                                                        @Param("counterpartyName") String counterpartyName,
                                                        @Param("direction") String direction,
                                                        @Param("counterpartyCompanyId") Long counterpartyCompanyId);
    default List<Map<String, Object>> selectMonthlyOrderSummary(Long companyId, String name, String direction) {
        return selectMonthlyOrderSummary(companyId, name, direction, null);
    }
}
