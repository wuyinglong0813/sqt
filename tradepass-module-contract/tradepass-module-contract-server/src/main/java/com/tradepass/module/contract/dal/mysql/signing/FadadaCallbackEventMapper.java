package com.tradepass.module.contract.dal.mysql.signing;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tradepass.module.contract.dal.dataobject.signing.FadadaCallbackEventDO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface FadadaCallbackEventMapper extends BaseMapper<FadadaCallbackEventDO> {
    @Update("""
            UPDATE fadada_callback_event
            SET status = 'PROCESSING', processing_token = #{token}, lease_until = #{leaseUntil},
                attempt_count = attempt_count + 1
            WHERE id = #{id} AND retry_payload IS NOT NULL AND (
                (status IN ('RECEIVED', 'FAILED') AND (next_attempt_at IS NULL OR next_attempt_at <= #{now}))
                OR (status = 'PROCESSING' AND lease_until <= #{now}))
            """)
    int claim(@Param("id") Long id, @Param("token") String token,
              @Param("now") LocalDateTime now, @Param("leaseUntil") LocalDateTime leaseUntil);

    @Select("""
            SELECT id FROM fadada_callback_event
            WHERE retry_payload IS NOT NULL AND (
                (status IN ('RECEIVED', 'FAILED') AND (next_attempt_at IS NULL OR next_attempt_at <= #{now}))
                OR (status = 'PROCESSING' AND lease_until <= #{now}))
            ORDER BY id LIMIT 20
            """)
    List<Long> findDue(@Param("now") LocalDateTime now);

    @Update("""
            UPDATE fadada_callback_event
            SET status = #{event.status}, subject_type = #{event.subjectType}, subject_id = #{event.subjectId},
                failure_reason = #{event.failureReason}, processed_at = #{event.processedAt},
                next_attempt_at = #{event.nextAttemptAt}, processing_token = NULL, lease_until = NULL
            WHERE id = #{event.id} AND status = 'PROCESSING' AND processing_token = #{token}
            """)
    int finish(@Param("event") FadadaCallbackEventDO event, @Param("token") String token);

    @Update("""
            UPDATE fadada_callback_event SET retry_payload = #{payload}, next_attempt_at = #{now}
            WHERE id = #{id} AND retry_payload IS NULL AND status IN ('RECEIVED', 'FAILED')
            """)
    int restoreLegacyPayload(@Param("id") Long id, @Param("payload") String payload,
                             @Param("now") LocalDateTime now);
}
