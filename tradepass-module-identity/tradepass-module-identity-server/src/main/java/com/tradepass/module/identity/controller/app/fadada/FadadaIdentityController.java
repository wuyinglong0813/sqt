package com.tradepass.module.identity.controller.app.fadada;

import com.tradepass.framework.common.pojo.ApiResponse;
import com.tradepass.module.identity.api.fadada.dto.FadadaAuthUrlRespDTO;
import com.tradepass.module.identity.api.fadada.dto.FadadaCompanyIdentityRespDTO;
import com.tradepass.module.identity.api.fadada.dto.LegalRepresentativeRespDTO;
import com.tradepass.module.identity.api.fadada.dto.PersonalIdentityRespDTO;
import com.tradepass.framework.common.pojo.ServiceUrlPayload;
import com.tradepass.module.identity.service.fadada.FadadaCompanyService;
import com.tradepass.module.identity.service.fadada.FadadaPersonalIdentityService;
import com.tradepass.module.identity.service.fadada.FadadaSigningMemberService;
import com.tradepass.module.identity.service.fadada.FadadaSigningAuthorizationService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/fadada")
public class FadadaIdentityController {
    private final FadadaPersonalIdentityService personalIdentityService;
    private final FadadaCompanyService companyService;
    private final FadadaSigningMemberService signingMemberService;
    private final FadadaSigningAuthorizationService signingAuthorizationService;

    public FadadaIdentityController(FadadaPersonalIdentityService personalIdentityService, FadadaCompanyService companyService,
                                    FadadaSigningMemberService signingMemberService,
                                    FadadaSigningAuthorizationService signingAuthorizationService) {
        this.personalIdentityService = personalIdentityService;
        this.companyService = companyService;
        this.signingMemberService = signingMemberService;
        this.signingAuthorizationService = signingAuthorizationService;
    }

    @GetMapping("/users/me/identity")
    public ApiResponse<PersonalIdentityRespDTO> currentIdentity() {
        return ApiResponse.ok(personalIdentityService.current());
    }

    @PostMapping("/users/me/identity/sync")
    public ApiResponse<PersonalIdentityRespDTO> syncIdentity() {
        return ApiResponse.ok(personalIdentityService.syncCurrent());
    }

    @PostMapping("/users/me/auth-url")
    public ApiResponse<FadadaAuthUrlRespDTO> createAuthUrl() {
        return ApiResponse.ok(personalIdentityService.createAuthUrl());
    }

    @GetMapping("/companies/{companyId}/identity")
    public ApiResponse<FadadaCompanyIdentityRespDTO> companyIdentity(@PathVariable long companyId) {
        return ApiResponse.ok(companyService.current(companyId));
    }

    @PostMapping("/companies/{companyId}/identity/sync")
    public ApiResponse<FadadaCompanyIdentityRespDTO> syncCompanyIdentity(@PathVariable long companyId) {
        return ApiResponse.ok(companyService.syncCurrent(companyId));
    }

    @PostMapping("/companies/{companyId}/auth-url")
    public ApiResponse<ServiceUrlPayload> companyAuthUrl(@PathVariable long companyId) {
        return ApiResponse.ok(companyService.createAuthUrl(companyId));
    }

    @PostMapping("/companies/{companyId}/seal-manage-url")
    public ApiResponse<ServiceUrlPayload> sealManageUrl(@PathVariable long companyId) {
        return ApiResponse.ok(companyService.createSealManageUrl(companyId));
    }

    @PostMapping("/companies/{companyId}/organization-manage-url")
    public ApiResponse<ServiceUrlPayload> organizationManageUrl(@PathVariable long companyId) {
        return ApiResponse.ok(signingMemberService.createOrganizationManageUrl(companyId));
    }

    @PostMapping("/companies/{companyId}/signing-member/sync")
    public ApiResponse<ServiceUrlPayload> syncSigningMember(@PathVariable long companyId) {
        return ApiResponse.ok(signingMemberService.syncCurrent(companyId));
    }

    @PostMapping("/companies/{companyId}/members/{userId}/seal-grant-url")
    public ApiResponse<ServiceUrlPayload> memberSealGrantUrl(@PathVariable long companyId, @PathVariable long userId) {
        return ApiResponse.ok(signingAuthorizationService.memberSealGrantUrl(companyId, userId));
    }

    @PostMapping("/companies/{companyId}/legal-representative/auth-url")
    public ApiResponse<ServiceUrlPayload> legalRepresentativeUrl(@PathVariable long companyId) {
        return ApiResponse.ok(companyService.createLegalRepresentativeUrl(companyId));
    }

    @PostMapping("/companies/{companyId}/legal-representative/sync")
    public ApiResponse<LegalRepresentativeRespDTO> syncLegalRepresentative(@PathVariable long companyId) {
        return ApiResponse.ok(companyService.syncLegalRepresentative(companyId));
    }
}
