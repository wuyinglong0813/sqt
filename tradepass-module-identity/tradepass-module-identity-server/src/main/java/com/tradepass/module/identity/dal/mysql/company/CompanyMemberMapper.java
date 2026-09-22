package com.tradepass.module.identity.dal.mysql.company;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tradepass.module.identity.dal.dataobject.company.CompanyMemberDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface CompanyMemberMapper extends BaseMapper<CompanyMemberDO> {
    @Select("""
        SELECT u.id AS userId, u.nickname, u.phone, m.company_id AS companyId,
               m.role_code AS roleCode, m.custom_permissions AS customPermissions, m.status
        FROM sys_user u
        LEFT JOIN company_member m ON u.id = m.user_id AND m.company_id = #{companyId}
        WHERE u.id = #{userId}
        """)
    Map<String, Object> selectMemberInfo(@Param("userId") Long userId, @Param("companyId") Long companyId);

    @Select("""
        SELECT u.id AS userId, u.nickname, u.phone, m.company_id AS companyId,
               m.role_code AS roleCode, m.custom_permissions AS customPermissions, m.status
        FROM sys_user u
        LEFT JOIN company_member m ON u.id = m.user_id
        WHERE u.id = #{userId}
        LIMIT 1
        """)
    Map<String, Object> selectMemberInfoAnyCompany(@Param("userId") Long userId);

    @Select("""
        SELECT c.id AS companyId, c.name AS companyName, m.role_code AS roleCode,
               m.role_codes AS roleCodes,
               COALESCE(r.name, m.role_code) AS roleName
        FROM company c
        JOIN company_member m ON c.id = m.company_id
        LEFT JOIN role_def r ON r.company_id = m.company_id AND r.code = m.role_code
        WHERE m.user_id = #{userId} AND m.status = 'ACTIVE'
        ORDER BY c.id
        """)
    List<Map<String, Object>> selectUserCompanies(@Param("userId") Long userId);

    @Select("""
        SELECT m.id, m.user_id AS userId, m.role_code AS roleCode,
               m.custom_permissions AS customPermissions, m.status, u.nickname, u.phone,
               i.verified_name AS verifiedName, i.local_status AS identityStatus
        FROM company_member m
        JOIN sys_user u ON m.user_id = u.id
        LEFT JOIN fadada_user_identity i ON i.user_id = u.id AND i.local_status = 'VERIFIED'
        WHERE m.company_id = #{companyId}
        ORDER BY m.status, m.id
        """)
    List<Map<String, Object>> selectAuthorizationRecords(@Param("companyId") Long companyId);

    @Select("""
        SELECT m.id, m.user_id AS userId, m.role_code AS roleCode,
               m.custom_permissions AS customPermissions, m.status, u.nickname, u.phone,
               i.verified_name AS verifiedName, i.local_status AS identityStatus
        FROM company_member m
        JOIN sys_user u ON m.user_id = u.id
        LEFT JOIN fadada_user_identity i ON i.user_id = u.id AND i.local_status = 'VERIFIED'
        WHERE m.company_id = #{companyId}
          AND (#{status} IS NULL OR #{status} = '' OR m.status = #{status})
        ORDER BY m.status, m.id
        LIMIT #{limit} OFFSET #{offset}
        """)
    List<Map<String, Object>> selectAuthorizationPageRecords(@Param("companyId") Long companyId,
                                                             @Param("status") String status,
                                                             @Param("limit") int limit,
                                                             @Param("offset") long offset);

    @Select("""
        SELECT u.id, u.nickname, u.phone,
               COALESCE(
                   MAX(CASE WHEN m.role_code = 'LEGAL' THEN 'LEGAL' END),
                   MAX(CASE WHEN m.role_code = 'ADMIN' THEN 'ADMIN' END),
                   MAX(m.role_code)
               ) AS roleCode
        FROM sys_user u
        LEFT JOIN company_member m ON u.id = m.user_id
        GROUP BY u.id, u.nickname, u.phone
        ORDER BY u.id
        """)
    List<Map<String, Object>> selectDevUsers();
}
