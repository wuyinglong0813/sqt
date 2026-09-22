package com.tradepass.module.contract.dal.mysql.template;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tradepass.module.contract.dal.dataobject.template.ContractTemplateDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface ContractTemplateMapper extends BaseMapper<ContractTemplateDO> {
    @Select("""
        SELECT t.id, t.company_id AS companyId, t.name, t.category, t.content, t.created_by AS createdBy, t.updated_by AS updatedBy,
               t.created_at AS createdAt, t.updated_at AS updatedAt,
               u1.nickname AS createdByName, u2.nickname AS updatedByName
        FROM contract_template t
        LEFT JOIN sys_user u1 ON t.created_by = u1.id
        LEFT JOIN sys_user u2 ON t.updated_by = u2.id
        WHERE t.company_id = #{companyId}
          AND (#{keyword} IS NULL OR #{keyword} = '' OR t.name LIKE CONCAT('%', #{keyword}, '%'))
          AND (#{category} IS NULL OR #{category} = '' OR #{category} = 'all' OR t.category = #{category})
        ORDER BY t.updated_at DESC
        """)
    List<Map<String, Object>> selectTemplateViews(@Param("companyId") Long companyId,
                                                  @Param("keyword") String keyword,
                                                  @Param("category") String category);

    @Select("""
        SELECT t.id, t.name, t.category, t.content, t.created_by AS createdBy, t.updated_by AS updatedBy,
               t.created_at AS createdAt, t.updated_at AS updatedAt,
               u1.nickname AS createdByName, u2.nickname AS updatedByName
        FROM contract_template t
        LEFT JOIN sys_user u1 ON t.created_by = u1.id
        LEFT JOIN sys_user u2 ON t.updated_by = u2.id
        WHERE t.company_id = #{companyId}
          AND (#{keyword} IS NULL OR #{keyword} = '' OR t.name LIKE CONCAT('%', #{keyword}, '%'))
          AND (#{category} IS NULL OR #{category} = '' OR #{category} = 'all' OR t.category = #{category})
        ORDER BY t.updated_at DESC
        LIMIT #{limit} OFFSET #{offset}
        """)
    List<Map<String, Object>> selectTemplatePageViews(@Param("companyId") Long companyId,
                                                      @Param("keyword") String keyword,
                                                      @Param("category") String category,
                                                      @Param("limit") int limit,
                                                      @Param("offset") long offset);

    @Select("""
        SELECT t.id, t.name, t.category, t.content, t.created_by AS createdBy, t.updated_by AS updatedBy,
               t.created_at AS createdAt, t.updated_at AS updatedAt,
               u1.nickname AS createdByName, u2.nickname AS updatedByName
        FROM contract_template t
        LEFT JOIN sys_user u1 ON t.created_by = u1.id
        LEFT JOIN sys_user u2 ON t.updated_by = u2.id
        WHERE t.id = #{id} AND t.company_id = #{companyId}
        """)
    Map<String, Object> selectTemplateView(@Param("id") Long id, @Param("companyId") Long companyId);
    @Select("""
        SELECT t.id, t.company_id AS companyId, t.name, t.category, t.content, t.created_by AS createdBy, t.updated_by AS updatedBy,
               t.created_at AS createdAt, t.updated_at AS updatedAt
        FROM contract_template t
        WHERE t.company_id = #{companyId}
          AND (#{keyword} IS NULL OR #{keyword} = '' OR t.name LIKE CONCAT('%', #{keyword}, '%'))
          AND (#{category} IS NULL OR #{category} = '' OR #{category} = 'all' OR t.category = #{category})
        ORDER BY t.updated_at DESC
        """)
    List<Map<String, Object>> selectTemplateViewsOwned(@Param("companyId") Long companyId,
                                                  @Param("keyword") String keyword,
                                                  @Param("category") String category);

    @Select("""
        SELECT t.id, t.name, t.category, t.content, t.created_by AS createdBy, t.updated_by AS updatedBy,
               t.created_at AS createdAt, t.updated_at AS updatedAt
        FROM contract_template t
        WHERE t.company_id = #{companyId}
          AND (#{keyword} IS NULL OR #{keyword} = '' OR t.name LIKE CONCAT('%', #{keyword}, '%'))
          AND (#{category} IS NULL OR #{category} = '' OR #{category} = 'all' OR t.category = #{category})
        ORDER BY t.updated_at DESC
        LIMIT #{limit} OFFSET #{offset}
        """)
    List<Map<String, Object>> selectTemplatePageViewsOwned(@Param("companyId") Long companyId,
                                                      @Param("keyword") String keyword,
                                                      @Param("category") String category,
                                                      @Param("limit") int limit,
                                                      @Param("offset") long offset);

    @Select("""
        SELECT t.id, t.name, t.category, t.content, t.created_by AS createdBy, t.updated_by AS updatedBy,
               t.created_at AS createdAt, t.updated_at AS updatedAt
        FROM contract_template t
        WHERE t.id = #{id} AND t.company_id = #{companyId}
        """)
    Map<String, Object> selectTemplateViewOwned(@Param("id") Long id, @Param("companyId") Long companyId);
}
