package com.tradepass.module.identity.controller.app.certification;

import com.tradepass.framework.common.pojo.ApiResponse;
import com.tradepass.module.identity.controller.app.certification.vo.CertificationReviewReqVO;
import com.tradepass.module.identity.api.certification.dto.CertificationApplicationRespDTO;
import com.tradepass.module.identity.service.certification.CompanyCertificationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class CompanyCertificationController {
    private final CompanyCertificationService certificationService;

    public CompanyCertificationController(CompanyCertificationService certificationService) {
        this.certificationService = certificationService;
    }

    @GetMapping("/me/company-certification-applications")
    public ApiResponse<List<CertificationApplicationRespDTO>> myApplications() {
        return ApiResponse.ok(certificationService.myApplications());
    }

    @PostMapping("/company-certifications/provider-callback")
    public ApiResponse<CertificationApplicationRespDTO> providerCallback(
            @RequestHeader(value = "X-Verification-Token", required = false) String token,
            @Valid @RequestBody CertificationReviewReqVO request) {
        return ApiResponse.ok(certificationService.review(token, request));
    }
}
