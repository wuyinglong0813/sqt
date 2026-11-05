package com.tradepass.module.identity.controller.app.company.vo;

import java.time.LocalDateTime;

/** Applicant-only summary. This never grants a company context or business permissions. */
public record MemberApplicationRespVO(String id, String companyId, String companyName, String status,
                                      boolean phoneBindingRequired, boolean personalIdentityRequired,
                                      LocalDateTime createdAt) {}
