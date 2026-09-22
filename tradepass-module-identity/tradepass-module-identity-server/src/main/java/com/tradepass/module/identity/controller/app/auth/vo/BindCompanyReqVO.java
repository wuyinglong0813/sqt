package com.tradepass.module.identity.controller.app.auth.vo;

import jakarta.validation.constraints.NotBlank;

public record BindCompanyReqVO(
        @NotBlank(message = "企业 ID 不能为空") String id,
        @NotBlank(message = "企业名称不能为空") String name,
        @NotBlank(message = "统一社会信用代码不能为空") String creditCode,
        @NotBlank(message = "法人姓名不能为空") String legalPersonName,
        String certificationStatus,
        String realNameStatus,
        String faceStatus,
        String sealStatus
) {
}
