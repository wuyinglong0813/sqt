package com.tradepass.module.identity.dal.mysql.fadada;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tradepass.module.identity.dal.dataobject.fadada.FadadaSigningMemberSyncDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface FadadaSigningMemberSyncMapper extends BaseMapper<FadadaSigningMemberSyncDO> {
    record Candidate(long companyId, long targetUserId, Long actorUserId) {}

    @Insert("""
        INSERT INTO fadada_signing_member_sync
            (id, company_id, user_id, requested_by, trigger_source, status, next_attempt_at)
        SELECT CONCAT(company_id, ':', user_id), company_id, user_id, #{actorUserId}, #{source}, 'PENDING', CURRENT_TIMESTAMP
        FROM company_member
        WHERE status = 'ACTIVE'
          AND (#{companyId} IS NULL OR company_id = #{companyId})
          AND (#{userId} IS NULL OR user_id = #{userId})
        ON DUPLICATE KEY UPDATE requested_by = VALUES(requested_by), trigger_source = VALUES(trigger_source),
            status = 'PENDING', next_attempt_at = LEAST(next_attempt_at, CURRENT_TIMESTAMP)
        """)
    int enqueue(@Param("companyId") Long companyId, @Param("userId") Long userId,
                @Param("actorUserId") Long actorUserId, @Param("source") String source);

    /** The durable local state is also a recovery source for missed events and legacy ACTIVE members. */
    @Insert("""
        INSERT IGNORE INTO fadada_signing_member_sync
            (id, company_id, user_id, trigger_source, status, next_attempt_at)
        SELECT CONCAT(m.company_id, ':', m.user_id), m.company_id, m.user_id, 'ACTIVE_MEMBER_BACKFILL', 'PENDING', CURRENT_TIMESTAMP
        FROM company_member m
        JOIN fadada_user_identity u ON u.user_id = m.user_id AND u.local_status = 'VERIFIED'
        JOIN fadada_corp_identity c ON c.company_id = m.company_id AND c.local_status = 'VERIFIED'
        LEFT JOIN fadada_signing_member_sync q ON q.company_id = m.company_id AND q.user_id = m.user_id
        WHERE m.status = 'ACTIVE' AND q.user_id IS NULL
        ORDER BY m.id LIMIT 50
        """)
    int backfill();

    /** Older READY rows attest membership only, not an effective signing-seal grant. */
    @Update("""
        UPDATE fadada_signing_member_sync
        SET status = 'PENDING', next_attempt_at = LEAST(next_attempt_at, CURRENT_TIMESTAMP)
        WHERE status = 'READY' AND (lease_until IS NULL OR lease_until < CURRENT_TIMESTAMP)
        """)
    int recheckLegacyReady();

    /** Runs with the local seal refresh transaction; a leased old check cannot consume this new work. */
    @Update("""
        UPDATE fadada_signing_member_sync
        SET status = 'PENDING', next_attempt_at = LEAST(next_attempt_at, CURRENT_TIMESTAMP)
        WHERE company_id = #{companyId}
        """)
    int invalidateCompanySealProof(@Param("companyId") long companyId);

    @Select("""
        SELECT company_id AS companyId, user_id AS targetUserId, requested_by AS actorUserId
        FROM fadada_signing_member_sync
        WHERE next_attempt_at <= CURRENT_TIMESTAMP
          AND (lease_until IS NULL OR lease_until < CURRENT_TIMESTAMP)
        ORDER BY next_attempt_at, company_id, user_id LIMIT 20
        """)
    List<Candidate> due();

    @Update("""
        UPDATE fadada_signing_member_sync
        SET lease_token = #{token}, lease_until = DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 5 MINUTE),
            status = 'SYNCING', attempt_count = attempt_count + 1
        WHERE company_id = #{companyId} AND user_id = #{userId}
          AND next_attempt_at <= CURRENT_TIMESTAMP
          AND (lease_until IS NULL OR lease_until < CURRENT_TIMESTAMP)
        """)
    int claim(@Param("companyId") long companyId, @Param("userId") long userId, @Param("token") String token);

    @Update("""
        UPDATE fadada_signing_member_sync
        SET next_attempt_at = CASE WHEN status = 'PENDING' THEN CURRENT_TIMESTAMP
                ELSE DATE_ADD(CURRENT_TIMESTAMP, INTERVAL #{retrySeconds} SECOND) END,
            status = CASE WHEN status = 'PENDING' THEN 'PENDING' ELSE #{status} END,
            last_error_code = #{code}, last_error = #{message},
            last_attempt_at = CURRENT_TIMESTAMP, lease_token = NULL, lease_until = NULL
        WHERE company_id = #{companyId} AND user_id = #{userId} AND lease_token = #{token}
        """)
    int finish(@Param("companyId") long companyId, @Param("userId") long userId, @Param("token") String token,
               @Param("status") String status, @Param("code") int code, @Param("message") String message,
               @Param("retrySeconds") int retrySeconds);
}
