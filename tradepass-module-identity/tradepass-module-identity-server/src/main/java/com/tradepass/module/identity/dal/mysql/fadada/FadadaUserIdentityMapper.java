package com.tradepass.module.identity.dal.mysql.fadada;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tradepass.module.identity.dal.dataobject.fadada.FadadaUserIdentityDO;

public interface FadadaUserIdentityMapper extends BaseMapper<FadadaUserIdentityDO> {
    @org.apache.ibatis.annotations.Select("""
            SELECT * FROM fadada_user_identity
            WHERE local_status IN ('VERIFIED', 'IN_PROGRESS', 'FAILED')
              AND (open_user_id IS NOT NULL OR ident_submitted_at IS NOT NULL)
              AND COALESCE(last_reconciled_at, last_sync_at, created_at, '1970-01-01') <
                  CASE WHEN local_status = 'VERIFIED' THEN #{verifiedBefore} ELSE #{pendingBefore} END
            ORDER BY COALESCE(last_reconciled_at, last_sync_at, created_at, '1970-01-01'), id LIMIT 10
            """)
    java.util.List<FadadaUserIdentityDO> findForReconciliation(
            @org.apache.ibatis.annotations.Param("verifiedBefore") java.time.LocalDateTime verifiedBefore,
            @org.apache.ibatis.annotations.Param("pendingBefore") java.time.LocalDateTime pendingBefore);

    @org.apache.ibatis.annotations.Update("""
            UPDATE fadada_user_identity SET last_reconciled_at = #{now}
            WHERE id = #{id} AND (last_reconciled_at IS NULL OR last_reconciled_at < #{before})
            """)
    int claimReconciliation(@org.apache.ibatis.annotations.Param("id") Long id,
            @org.apache.ibatis.annotations.Param("now") java.time.LocalDateTime now,
            @org.apache.ibatis.annotations.Param("before") java.time.LocalDateTime before);
}
