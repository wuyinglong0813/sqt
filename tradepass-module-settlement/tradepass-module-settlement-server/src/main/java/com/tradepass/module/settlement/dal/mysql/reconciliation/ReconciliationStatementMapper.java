package com.tradepass.module.settlement.dal.mysql.reconciliation;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tradepass.module.settlement.dal.dataobject.reconciliation.ReconciliationStatementDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ReconciliationStatementMapper extends BaseMapper<ReconciliationStatementDO> {
    @Select("""
            SELECT statement.id, statement.issuer_company_id, statement.counterparty_company_id,
                   statement.statement_period, statement.original_name, statement.content_type,
                   statement.file_size, statement.remark, statement.created_at,
                   issuer.name AS issuer_company_name,
                   CASE WHEN statement.issuer_company_id = #{companyId} THEN counterparty.name ELSE issuer.name END AS counterparty_name
            FROM reconciliation_statement statement
            JOIN company issuer ON issuer.id = statement.issuer_company_id
            JOIN company counterparty ON counterparty.id = statement.counterparty_company_id
            WHERE (statement.issuer_company_id = #{companyId} OR statement.counterparty_company_id = #{companyId})
            ORDER BY statement.statement_period DESC, statement.created_at DESC, statement.id DESC
            """)
    List<ReconciliationStatementDO> selectByCompany(@Param("companyId") long companyId);

    @Select("""
            SELECT statement.id, statement.issuer_company_id, statement.counterparty_company_id,
                   statement.statement_period, statement.original_name, statement.content_type,
                   statement.file_size, statement.remark, statement.created_at,
                   issuer.name AS issuer_company_name,
                   CASE WHEN statement.issuer_company_id = #{companyId} THEN counterparty.name ELSE issuer.name END AS counterparty_name
            FROM reconciliation_statement statement
            JOIN company issuer ON issuer.id = statement.issuer_company_id
            JOIN company counterparty ON counterparty.id = statement.counterparty_company_id
            WHERE (statement.issuer_company_id = #{companyId} OR statement.counterparty_company_id = #{companyId})
              AND ((statement.issuer_company_id = #{companyId} AND statement.counterparty_company_id = #{counterpartyCompanyId})
                OR (statement.issuer_company_id = #{counterpartyCompanyId} AND statement.counterparty_company_id = #{companyId}))
            ORDER BY statement.statement_period DESC, statement.created_at DESC, statement.id DESC
            """)
    List<ReconciliationStatementDO> selectByCompanyPair(@Param("companyId") long companyId,
                                                        @Param("counterpartyCompanyId") Long counterpartyCompanyId);

    @Select("""
            SELECT statement.id, statement.issuer_company_id, statement.counterparty_company_id,
                   statement.statement_period, statement.original_name, statement.content_type,
                   statement.file_size, statement.remark, statement.created_at,
                   NULL AS issuer_company_name, NULL AS counterparty_name
            FROM reconciliation_statement statement
            WHERE (statement.issuer_company_id = #{companyId} OR statement.counterparty_company_id = #{companyId})
            ORDER BY statement.statement_period DESC, statement.created_at DESC, statement.id DESC
            """)
    List<ReconciliationStatementDO> selectByCompanyWithoutJoin(@Param("companyId") long companyId);

    @Select("""
            SELECT statement.id, statement.issuer_company_id, statement.counterparty_company_id,
                   statement.statement_period, statement.original_name, statement.content_type,
                   statement.file_size, statement.remark, statement.created_at,
                   NULL AS issuer_company_name, NULL AS counterparty_name
            FROM reconciliation_statement statement
            WHERE (statement.issuer_company_id = #{companyId} OR statement.counterparty_company_id = #{companyId})
              AND ((statement.issuer_company_id = #{companyId} AND statement.counterparty_company_id = #{counterpartyCompanyId})
                OR (statement.issuer_company_id = #{counterpartyCompanyId} AND statement.counterparty_company_id = #{companyId}))
            ORDER BY statement.statement_period DESC, statement.created_at DESC, statement.id DESC
            """)
    List<ReconciliationStatementDO> selectByCompanyPairWithoutJoin(@Param("companyId") long companyId,
                                                                   @Param("counterpartyCompanyId") Long counterpartyCompanyId);

    @Select("""
            SELECT id, issuer_company_id, counterparty_company_id,
                   original_name, content_type, file_size, file_data, sha256,
                   storage_bucket, object_key, object_version_id
            FROM reconciliation_statement WHERE id = #{id}
            """)
    ReconciliationStatementDO selectFile(@Param("id") Long id);
}
