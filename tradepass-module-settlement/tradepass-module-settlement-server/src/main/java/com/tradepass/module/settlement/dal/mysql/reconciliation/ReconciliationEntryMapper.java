package com.tradepass.module.settlement.dal.mysql.reconciliation;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tradepass.module.settlement.dal.dataobject.reconciliation.ReconciliationEntryDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ReconciliationEntryMapper extends BaseMapper<ReconciliationEntryDO> {
    @Insert("""
            INSERT INTO reconciliation_entry
            (id, company_a_id, company_b_id, contract_id, source_type, source_id,
             business_date, document_no, amount, supplier_company_id, buyer_company_id,
             issuer_company_id, approved_by, approved_at)
            VALUES (#{id}, #{companyAId}, #{companyBId}, #{contractId}, #{sourceType}, #{sourceId},
                    #{businessDate}, #{documentNo}, #{amount}, #{supplierCompanyId}, #{buyerCompanyId},
                    #{issuerCompanyId}, #{approvedBy}, #{approvedAt})
            ON DUPLICATE KEY UPDATE source_id = VALUES(source_id)
            """)
    int upsertEntry(ReconciliationEntryDO row);

    @Insert("""
            INSERT INTO reconciliation_entry
            (id, company_a_id, company_b_id, contract_id, source_type, source_id,
             business_date, document_no, amount, supplier_company_id, buyer_company_id,
             issuer_company_id, approved_by, approved_at, reversal_of_id, action_request_id)
            VALUES (#{id}, #{companyAId}, #{companyBId}, #{contractId}, #{sourceType}, #{sourceId},
                    #{businessDate}, #{documentNo}, #{amount}, #{supplierCompanyId}, #{buyerCompanyId},
                    #{issuerCompanyId}, #{approvedBy}, #{approvedAt}, #{reversalOfId}, #{actionRequestId})
            ON DUPLICATE KEY UPDATE reversal_of_id = VALUES(reversal_of_id)
            """)
    int upsertReversal(ReconciliationEntryDO row);

    @Select("""
            SELECT id, company_a_id, company_b_id, contract_id, source_type,
                   business_date, document_no, amount, supplier_company_id,
                   buyer_company_id, issuer_company_id
            FROM reconciliation_entry
            WHERE source_type = #{sourceType} AND source_id = #{sourceId} AND reversal_of_id IS NULL
            LIMIT 1
            """)
    ReconciliationEntryDO selectReversalSource(@Param("sourceType") String sourceType,
                                               @Param("sourceId") long sourceId);

    @Select("""
            SELECT DISTINCT contract_id FROM reconciliation_entry
            WHERE company_a_id = #{companyAId} AND company_b_id = #{companyBId}
            """)
    List<Long> selectContractIds(@Param("companyAId") long companyAId,
                                 @Param("companyBId") long companyBId);

    @Select("""
            SELECT contract_id, source_type, business_date, amount
            FROM reconciliation_entry
            WHERE company_a_id = #{companyAId} AND company_b_id = #{companyBId}
            ORDER BY contract_id, business_date, approved_at, id
            """)
    List<ReconciliationEntryDO> selectWorkbookEntries(@Param("companyAId") long companyAId,
                                                      @Param("companyBId") long companyBId);

    @Select("""
            SELECT entry.id, entry.contract_id, entry.source_type, entry.source_id,
                   entry.business_date, entry.document_no, entry.amount,
                   entry.supplier_company_id, entry.buyer_company_id,
                   entry.issuer_company_id, entry.approved_at,
                   contract.contract_no
            FROM reconciliation_entry entry
            LEFT JOIN trade_contract contract ON contract.id = entry.contract_id
            WHERE entry.company_a_id = #{companyAId} AND entry.company_b_id = #{companyBId}
            ORDER BY entry.business_date DESC, entry.approved_at DESC, entry.id DESC
            """)
    List<ReconciliationEntryDO> selectAccountEntriesWithContract(@Param("companyAId") long companyAId,
                                                                 @Param("companyBId") long companyBId);

    @Select("""
            SELECT entry.id, entry.contract_id, entry.source_type, entry.source_id,
                   entry.business_date, entry.document_no, entry.amount,
                   entry.supplier_company_id, entry.buyer_company_id,
                   entry.issuer_company_id, entry.approved_at,
                   NULL AS contract_no
            FROM reconciliation_entry entry
            WHERE entry.company_a_id = #{companyAId} AND entry.company_b_id = #{companyBId}
            ORDER BY entry.business_date DESC, entry.approved_at DESC, entry.id DESC
            """)
    List<ReconciliationEntryDO> selectAccountEntries(@Param("companyAId") long companyAId,
                                                     @Param("companyBId") long companyBId);
}
