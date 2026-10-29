package com.tradepass.module.identity.controller.app.company.vo;

public record InvitePreviewRespVO(String companyId, String companyName, String type,
                                  boolean personalIdentityRequired, String targetPhoneMasked,
                                  boolean phoneBindingRequired) {}
