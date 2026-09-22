package com.tradepass.module.identity.controller.app.company;

import com.tradepass.framework.common.pojo.TradePassDtos;

import com.tradepass.framework.common.pojo.ApiResponse;
import com.tradepass.framework.common.pojo.TradePassDtos.AuthorizationRecord;
import com.tradepass.framework.common.pojo.TradePassDtos.CompanyProfile;
import com.tradepass.framework.common.pojo.TradePassDtos.CompanySearchSummary;
import com.tradepass.framework.common.pojo.TradePassDtos.SealRecord;
import com.tradepass.module.identity.controller.app.company.vo.ApproveReqVO;
import com.tradepass.module.identity.controller.app.company.vo.CompanySubmitReqVO;
import com.tradepass.module.identity.controller.app.company.vo.InviteReqVO;
import com.tradepass.module.identity.controller.app.company.vo.JoinReqVO;
import com.tradepass.module.identity.controller.app.company.vo.RoleReqVO;
import com.tradepass.module.identity.controller.app.company.vo.SealReqVO;
import com.tradepass.module.identity.controller.app.company.vo.VerificationReqVO;
import com.tradepass.module.identity.api.company.dto.InviteResult;
import com.tradepass.module.identity.api.company.dto.JoinResult;
import com.tradepass.framework.common.pojo.PagePayload;
import com.tradepass.module.identity.api.certification.dto.CertificationApplicationRespDTO;
import com.tradepass.module.identity.api.permission.dto.RoleRespDTO;
import com.tradepass.module.identity.service.certification.CompanyCertificationService;
import com.tradepass.module.identity.service.company.CompanyService;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class CompanyController {
    private final CompanyService companyService;
    private final CompanyCertificationService certificationService;

    @Autowired
    public CompanyController(CompanyService companyService, CompanyCertificationService certificationService) {
        this.companyService = companyService;
        this.certificationService = certificationService;
    }

    CompanyController(CompanyService companyService) {
        this(companyService, null);
    }

    @GetMapping("/companies/search")
    public ApiResponse<List<CompanySearchSummary>> searchCompanies(@RequestParam String keyword) {
        return ApiResponse.ok(companyService.searchCompanies(keyword));
    }

    @GetMapping("/me/company-onboarding")
    public ApiResponse<List<CompanyProfile>> myOnboardingCompanies() {
        return ApiResponse.ok(companyService.myOnboardingCompanies());
    }

    @GetMapping("/companies/{id}")
    public ApiResponse<CompanyProfile> getCompany(@PathVariable String id) {
        return ApiResponse.ok(companyService.getCompany(id));
    }

    @PostMapping("/companies")
    public ApiResponse<CompanyProfile> submitCompany(@Valid @RequestBody CompanySubmitReqVO request) {
        return ApiResponse.ok(companyService.submitCompany(request));
    }

    @PostMapping("/companies/{id}/certifications")
    public ApiResponse<CertificationApplicationRespDTO> submitCertification(@PathVariable String id) {
        if (certificationService == null) {
            throw new IllegalStateException("企业认证服务未配置");
        }
        return ApiResponse.ok(certificationService.submit(id));
    }

    @GetMapping("/ca/config")
    public ApiResponse<Map<String, Boolean>> caConfig() {
        return ApiResponse.ok(Map.of("mockEnabled", companyService.caMockEnabled()));
    }

    @PostMapping("/verifications/real-name")
    public ApiResponse<CompanyProfile> verifyRealName(@Valid @RequestBody VerificationReqVO request) {
        return ApiResponse.ok(companyService.verifyRealName(request));
    }

    @PostMapping("/verifications/face")
    public ApiResponse<CompanyProfile> verifyFace(@Valid @RequestBody VerificationReqVO request) {
        return ApiResponse.ok(companyService.verifyFace(request));
    }

    @PostMapping("/seals")
    public ApiResponse<SealRecord> uploadSeal(@Valid @RequestBody SealReqVO request) {
        return ApiResponse.ok(companyService.uploadSeal(request));
    }

    @PostMapping("/companies/invite")
    public ApiResponse<InviteResult> createInvite(@Valid @RequestBody InviteReqVO request) {
        return ApiResponse.ok(companyService.createInvite(request));
    }

    @PostMapping("/companies/counterparty-invite")
    public ApiResponse<InviteResult> createCounterpartyInvite(@Valid @RequestBody InviteReqVO request) {
        return ApiResponse.ok(companyService.createCounterpartyInvite(request));
    }

    @PostMapping("/companies/join")
    public ApiResponse<JoinResult> joinCompany(@Valid @RequestBody JoinReqVO request) {
        return ApiResponse.ok(companyService.joinCompany(request));
    }

    @GetMapping("/authorizations")
    public ApiResponse<PagePayload<AuthorizationRecord>> listMembers(@RequestParam String companyId,
                                                                     @RequestParam(required = false) String status,
                                                                     @RequestParam(defaultValue = "1") int page,
                                                                     @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(companyService.pageMembers(companyId, status, page, size));
    }

    @PostMapping("/authorizations/{id}/approve")
    public ApiResponse<AuthorizationRecord> approveMember(@PathVariable String id,
                                                          @Valid @RequestBody ApproveReqVO request,
                                                          @RequestParam String companyId) {
        return ApiResponse.ok(companyService.approveMember(id, request, companyId));
    }

    @PostMapping("/authorizations/{id}/reject")
    public ApiResponse<Void> rejectMember(@PathVariable String id, @RequestParam String companyId) {
        companyService.rejectMember(id, companyId);
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/authorizations/{id}")
    public ApiResponse<Void> removeMember(@PathVariable String id, @RequestParam String companyId) {
        companyService.removeMember(id, companyId);
        return ApiResponse.ok(null);
    }

    @PutMapping("/authorizations/{id}/role")
    public ApiResponse<Void> updateMemberRole(@PathVariable String id, @Valid @RequestBody ApproveReqVO request,
                                               @RequestParam String companyId) {
        companyService.updateMemberRole(id, request, companyId);
        return ApiResponse.ok(null);
    }

    @GetMapping("/roles")
    public ApiResponse<List<RoleRespDTO>> listRoles(@RequestParam String companyId) {
        return ApiResponse.ok(companyService.listRoles(companyId));
    }

    @PostMapping("/roles")
    public ApiResponse<RoleRespDTO> createRole(@Valid @RequestBody RoleReqVO request) {
        return ApiResponse.ok(companyService.createRole(request));
    }

    @PutMapping("/roles/{id}")
    public ApiResponse<Void> updateRole(@PathVariable String id, @Valid @RequestBody RoleReqVO request) {
        companyService.updateRole(id, request);
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/roles/{id}")
    public ApiResponse<Void> deleteRole(@PathVariable String id) {
        companyService.deleteRole(id);
        return ApiResponse.ok(null);
    }
}
