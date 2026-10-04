package com.tradepass.module.contract.dal.mysql.contract;

import com.tradepass.module.contract.dal.mysql.signing.ContractSigningTodoSql;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tradepass.module.contract.dal.dataobject.contract.TradeContractDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Map;
import java.util.List;

@Mapper
public interface TradeContractMapper extends BaseMapper<TradeContractDO> {
    @Select("""
        SELECT CASE WHEN t.company_id = #{companyId} THEN t.counterparty_company_id
                    ELSE t.company_id END AS counterpartyCompanyId,
               COUNT(*) AS contractCount
        FROM trade_contract t
        WHERE (t.company_id = #{companyId} OR t.counterparty_company_id = #{companyId})
          AND t.status IN ('ACTIVE', 'COMPLETED')
          AND (t.company_id <> #{companyId} OR COALESCE(t.initiator_hidden, 0) = 0)
        """ + ContractViewerDirectionSql.FILTER + """
        GROUP BY counterpartyCompanyId
        """)
    List<Map<String, Object>> selectSignedTradeContractCounts(@Param("companyId") Long companyId,
                                                             @Param("viewerDirection") String viewerDirection);

    @Select("""
        SELECT CASE WHEN t.company_id = #{companyId} THEN t.counterparty_company_id
                    ELSE t.company_id END AS counterpartyCompanyId,
               CASE WHEN t.company_id = #{companyId} THEN t.counterparty_name
                    ELSE NULL END AS counterpartyName,
               SUM(t.amount) AS totalAmount, COUNT(*) AS orderCount
        FROM trade_contract t
        WHERE (t.company_id = #{companyId} OR t.counterparty_company_id = #{companyId})
          AND t.status IN ('ACTIVE', 'COMPLETED')
          AND (t.company_id <> #{companyId} OR COALESCE(t.initiator_hidden, 0) = 0)
        """ + ContractViewerDirectionSql.FILTER + """
          AND ((#{period} = 'year' AND YEAR(COALESCE(t.approved_at, t.created_at)) = YEAR(CURDATE()))
            OR (#{period} = 'month' AND YEAR(COALESCE(t.approved_at, t.created_at)) = YEAR(CURDATE())
                AND MONTH(COALESCE(t.approved_at, t.created_at)) = MONTH(CURDATE()))
            OR (#{period} = 'last12' AND COALESCE(t.approved_at, t.created_at) >=
                DATE_FORMAT(DATE_SUB(CURDATE(), INTERVAL 11 MONTH), '%Y-%m-01')))
        GROUP BY counterpartyCompanyId, counterpartyName
        """)
    List<Map<String, Object>> selectSignedTradeRanking(@Param("companyId") Long companyId,
                                                      @Param("viewerDirection") String viewerDirection,
                                                      @Param("period") String period);

    @Select("SELECT contract.* " + ContractSigningTodoSql.FROM_AND_WHERE
            + " ORDER BY contract.created_at DESC, contract.id DESC")
    List<TradeContractDO> selectContractsAwaitingSignature(@Param("companyId") long companyId);

    @Select("SELECT COUNT(1) " + ContractSigningTodoSql.FROM_AND_WHERE)
    long countContractsAwaitingSignature(@Param("companyId") long companyId);

    @Select("SELECT * FROM trade_contract WHERE id = #{id} FOR UPDATE")
    TradeContractDO selectByIdForUpdate(@Param("id") Long id);

    @Select("SELECT COUNT(1) FROM trade_contract WHERE id = #{id} AND " + ContractSigningTodoSql.COUNTERPARTY_RELEASED)
    long countReleasedToCounterparty(@Param("id") Long id);

    @Select("""
        SELECT COUNT(*) AS total,
               COALESCE(SUM(CASE WHEN status = 'PENDING' THEN 1 ELSE 0 END), 0) AS pending,
               COALESCE(SUM(CASE WHEN status = 'ACTIVE' THEN 1 ELSE 0 END), 0) AS active,
               COALESCE(SUM(amount), 0) AS amount
        FROM trade_contract
        WHERE ((company_id = #{companyId} AND COALESCE(initiator_hidden, 0) = 0)
               OR (counterparty_company_id = #{companyId}
                   AND status NOT IN ('REJECTED', 'CANCELLED', 'DELETED')
                   AND """ + ContractSigningTodoSql.COUNTERPARTY_RELEASED + """
               ))
        """)
    Map<String, Object> selectContractSummary(@Param("companyId") Long companyId);

    @Select("""
        SELECT t.*
        FROM trade_contract t
        JOIN company initiator ON initiator.id = t.company_id
        WHERE ((t.company_id = #{companyId} AND COALESCE(t.initiator_hidden, 0) = 0)
               OR (t.counterparty_company_id = #{companyId}
                   AND t.status NOT IN ('REJECTED', 'CANCELLED', 'DELETED')
                   AND """ + ContractSigningTodoSql.COUNTERPARTY_RELEASED_T + """
               ))
          AND (#{counterpartyName} IS NULL OR #{counterpartyName} = '' OR
               (t.company_id = #{companyId} AND t.counterparty_name = #{counterpartyName}) OR
               (t.counterparty_company_id = #{companyId} AND initiator.name = #{counterpartyName}))
          AND (#{status} IS NULL OR #{status} = '' OR t.status = #{status})
        """ + ContractViewerDirectionSql.FILTER + """
        ORDER BY t.created_at DESC, t.id DESC
        LIMIT #{limit} OFFSET #{offset}
        """)
    List<TradeContractDO> selectPartyContracts(@Param("companyId") Long companyId,
                                             @Param("counterpartyName") String counterpartyName,
                                             @Param("status") String status,
                                             @Param("viewerDirection") String viewerDirection,
                                             @Param("limit") int limit,
                                             @Param("offset") long offset);

    @Select("""
        SELECT COUNT(*)
        FROM trade_contract t
        JOIN company initiator ON initiator.id = t.company_id
        WHERE ((t.company_id = #{companyId} AND COALESCE(t.initiator_hidden, 0) = 0)
               OR (t.counterparty_company_id = #{companyId}
                   AND t.status NOT IN ('REJECTED', 'CANCELLED', 'DELETED')
                   AND """ + ContractSigningTodoSql.COUNTERPARTY_RELEASED_T + """
               ))
          AND (#{counterpartyName} IS NULL OR #{counterpartyName} = '' OR
               (t.company_id = #{companyId} AND t.counterparty_name = #{counterpartyName}) OR
               (t.counterparty_company_id = #{companyId} AND initiator.name = #{counterpartyName}))
          AND (#{status} IS NULL OR #{status} = '' OR t.status = #{status})
        """ + ContractViewerDirectionSql.FILTER + """
        """)
    long countPartyContracts(@Param("companyId") Long companyId,
                             @Param("counterpartyName") String counterpartyName,
                             @Param("status") String status,
                             @Param("viewerDirection") String viewerDirection);
}
