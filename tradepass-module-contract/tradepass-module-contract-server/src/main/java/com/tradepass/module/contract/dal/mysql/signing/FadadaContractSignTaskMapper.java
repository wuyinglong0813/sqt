package com.tradepass.module.contract.dal.mysql.signing;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tradepass.module.contract.dal.dataobject.signing.FadadaContractSignTaskDO;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDateTime;
import java.util.List;

public interface FadadaContractSignTaskMapper extends BaseMapper<FadadaContractSignTaskDO> {
    @Select("""
            SELECT t.* FROM fadada_contract_sign_task t
            JOIN trade_contract c ON c.id = t.contract_id AND COALESCE(c.version_no, 1) = t.version_no
            WHERE c.status IN ('PENDING', 'ACTIVE') AND t.sign_task_id IS NOT NULL AND t.sign_task_id <> ''
              AND ((c.status = 'PENDING' AND t.provider_status NOT IN ('task_terminated', 'expired', 'task_expired')
                    AND t.updated_at <= #{pendingBefore})
                OR (c.status = 'ACTIVE' AND (t.abolished_sign_task_id IS NOT NULL
                    OR t.provider_status = 'ABOLISH_CREATION_UNCERTAIN') AND t.updated_at <= #{pendingBefore})
                OR (c.status = 'ACTIVE' AND t.updated_at <= #{activeBefore}))
            ORDER BY t.updated_at, t.id LIMIT #{limit}
            """)
    List<FadadaContractSignTaskDO> findDueReconciliation(@Param("pendingBefore") LocalDateTime pendingBefore,
                                                       @Param("activeBefore") LocalDateTime activeBefore,
                                                       @Param("limit") int limit);

    @Update("""
            UPDATE fadada_contract_sign_task SET updated_at = #{now}
            WHERE id = #{id} AND updated_at = #{previous}
            """)
    int claimReconciliation(@Param("id") Long id, @Param("previous") LocalDateTime previous,
                            @Param("now") LocalDateTime now);
}
