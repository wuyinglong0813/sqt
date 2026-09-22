package com.tradepass.module.trade.dal.mysql.document;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tradepass.module.trade.dal.dataobject.document.BusinessDocumentDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface BusinessDocumentMapper extends BaseMapper<BusinessDocumentDO> {
    @Select("SELECT * FROM business_document WHERE id = #{id} FOR UPDATE")
    BusinessDocumentDO selectByIdForUpdate(@Param("id") Long id);

    @Select("""
            SELECT COUNT(*) FROM business_document
            WHERE recipient_company_id = #{companyId}
              AND document_type IN ('SALES_ORDER', 'RETURN_ORDER') AND status = 'ISSUED'
            """)
    long pendingDocumentCount(@Param("companyId") long companyId);

    @Select("""
            SELECT * FROM business_document
            WHERE recipient_company_id = #{companyId}
              AND document_type IN ('SALES_ORDER', 'RETURN_ORDER') AND status = 'ISSUED'
            ORDER BY created_at DESC, id DESC LIMIT 1
            """)
    BusinessDocumentDO latestPendingDocument(@Param("companyId") long companyId);
}
