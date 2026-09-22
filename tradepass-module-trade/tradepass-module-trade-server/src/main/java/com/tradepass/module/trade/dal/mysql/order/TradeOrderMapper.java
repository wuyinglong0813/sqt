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
        SELECT counterparty_name AS counterpartyName, SUM(amount) AS totalAmount, COUNT(1) AS orderCount
        FROM trade_order
        WHERE company_id = #{companyId}
          AND direction = #{direction}
          AND (#{period} = 'total'
            OR (#{period} = 'year' AND YEAR(order_date) = YEAR(CURDATE()))
            OR (#{period} = 'month' AND YEAR(order_date) = YEAR(CURDATE()) AND MONTH(order_date) = MONTH(CURDATE()))
            OR (#{period} = 'last12' AND order_date >= DATE_FORMAT(DATE_SUB(CURDATE(), INTERVAL 11 MONTH), '%Y-%m-01')))
        GROUP BY counterparty_name
        ORDER BY totalAmount DESC
        """)
    List<Map<String, Object>> selectRanking(@Param("companyId") Long companyId,
                                            @Param("direction") String direction,
                                            @Param("period") String period);

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

    @Select("""
        SELECT DATE_FORMAT(order_date, '%Y-%m') AS period, COALESCE(SUM(amount), 0) AS amount
        FROM trade_order
        WHERE company_id = #{companyId}
          AND counterparty_name = #{counterpartyName}
          AND direction = #{direction}
          AND order_date >= DATE_FORMAT(DATE_SUB(CURDATE(), INTERVAL 11 MONTH), '%Y-%m-01')
        GROUP BY DATE_FORMAT(order_date, '%Y-%m')
        ORDER BY period
        """)
    List<Map<String, Object>> selectMonthlyOrderSummary(@Param("companyId") Long companyId,
                                                        @Param("counterpartyName") String counterpartyName,
                                                        @Param("direction") String direction);
}
