package com.tradepass.module.identity.dal.mysql.counterparty;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tradepass.module.identity.dal.dataobject.counterparty.CounterpartyRelationEntityDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface CounterpartyRelationMapper extends BaseMapper<CounterpartyRelationEntityDO> {
    @Select("""
        SELECT r.id, r.counterparty_company_id AS counterpartyCompanyId,
               c.name AS counterpartyName,
               'SUPPLIER' AS relationType, r.status
        FROM counterparty_relation r
        JOIN company c ON c.id = r.counterparty_company_id
        WHERE r.company_id = #{companyId}
          AND r.counterparty_company_id IS NOT NULL
          AND r.status = 'ACTIVE'
        ORDER BY r.id
        """)
    List<Map<String, Object>> selectBuyerCounterparties(@Param("companyId") Long companyId);

    @Select("""
        SELECT r.id, r.company_id AS counterpartyCompanyId,
               c.name AS counterpartyName,
               'CUSTOMER' AS relationType, r.status
        FROM counterparty_relation r
        JOIN company c ON c.id = r.company_id
        WHERE r.counterparty_company_id = #{companyId}
          AND r.status = 'ACTIVE'
        ORDER BY r.id
        """)
    List<Map<String, Object>> selectSupplierCounterparties(@Param("companyId") Long companyId);

    @Select("""
        SELECT COUNT(*)
        FROM counterparty_relation
        WHERE status = 'ACTIVE'
          AND ((company_id = #{leftCompanyId} AND counterparty_company_id = #{rightCompanyId})
            OR (company_id = #{rightCompanyId} AND counterparty_company_id = #{leftCompanyId}))
        """)
    long countActiveBetween(@Param("leftCompanyId") Long leftCompanyId,
                            @Param("rightCompanyId") Long rightCompanyId);
}
