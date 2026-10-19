package com.tradepass.module.identity.dal.mysql.fadada;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tradepass.module.identity.dal.dataobject.fadada.FadadaCorpIdentityDO;

public interface FadadaCorpIdentityMapper extends BaseMapper<FadadaCorpIdentityDO> {
    @org.apache.ibatis.annotations.Select("""
            SELECT * FROM fadada_corp_identity
            WHERE local_status IN ('VERIFIED', 'IN_PROGRESS', 'FAILED')
              AND (open_corp_id IS NOT NULL OR submitted_at IS NOT NULL)
              AND COALESCE(last_reconciled_at, last_sync_at, created_at, '1970-01-01') <
                  CASE WHEN COALESCE(seal_sync_warning, '') <> '' THEN #{warningBefore}
                       WHEN local_status = 'VERIFIED' THEN #{verifiedBefore} ELSE #{pendingBefore} END
            ORDER BY COALESCE(last_reconciled_at, last_sync_at, created_at, '1970-01-01'), id LIMIT 10
            """)
    java.util.List<FadadaCorpIdentityDO> findForReconciliation(
            @org.apache.ibatis.annotations.Param("verifiedBefore") java.time.LocalDateTime verifiedBefore,
            @org.apache.ibatis.annotations.Param("pendingBefore") java.time.LocalDateTime pendingBefore,
            @org.apache.ibatis.annotations.Param("warningBefore") java.time.LocalDateTime warningBefore);

    @org.apache.ibatis.annotations.Update("""
            UPDATE fadada_corp_identity SET last_reconciled_at = #{now}
            WHERE id = #{id} AND (last_reconciled_at IS NULL OR last_reconciled_at < #{before})
            """)
    int claimReconciliation(@org.apache.ibatis.annotations.Param("id") Long id,
            @org.apache.ibatis.annotations.Param("now") java.time.LocalDateTime now,
            @org.apache.ibatis.annotations.Param("before") java.time.LocalDateTime before);
}
