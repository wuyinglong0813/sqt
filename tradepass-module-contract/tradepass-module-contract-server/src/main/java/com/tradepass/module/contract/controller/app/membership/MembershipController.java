package com.tradepass.module.contract.controller.app.membership;

import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.common.pojo.ApiResponse;
import com.tradepass.module.contract.service.membership.MembershipService;
import com.tradepass.module.identity.api.company.CompanyReader;
import com.tradepass.module.identity.api.company.dto.CompanyRespDTO;
import com.tradepass.module.identity.api.permission.AccessControlOperations;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/membership")
public class MembershipController {
    private final MembershipService membership;
    private final CompanyReader companies;
    private final AccessControlOperations access;
    public MembershipController(MembershipService membership, CompanyReader companies, AccessControlOperations access) {
        this.membership = membership; this.companies = companies; this.access = access;
    }
    @GetMapping
    public ApiResponse<MembershipService.Status> current() { return ApiResponse.ok(membership.status(company())); }
    @GetMapping("/usage")
    public ApiResponse<List<Map<String, Object>>> usage() { return ApiResponse.ok(membership.history(company())); }
    private CompanyRespDTO company() {
        long id = AuthContext.requireCompanyId();
        if (!access.isActiveMember(id, AuthContext.userId())) throw new BusinessException("仅当前企业有效成员可查看会员");
        CompanyRespDTO result = companies.selectById(id);
        if (result == null) throw new BusinessException("企业不存在");
        return result;
    }
}
