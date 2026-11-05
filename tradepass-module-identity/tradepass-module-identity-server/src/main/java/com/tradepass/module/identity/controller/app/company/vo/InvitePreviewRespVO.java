package com.tradepass.module.identity.controller.app.company.vo;

public record InvitePreviewRespVO(String companyId, String companyName, String type,
                                  boolean personalIdentityRequired, String targetPhoneMasked,
                                  boolean phoneBindingRequired, String invitationMode,
                                  java.time.LocalDateTime expiresAt) {
    public InvitePreviewRespVO(String companyId, String companyName, String type,
                               boolean personalIdentityRequired, String targetPhoneMasked,
                               boolean phoneBindingRequired) {
        this(companyId, companyName, type, personalIdentityRequired, targetPhoneMasked,
                phoneBindingRequired, null, null);
    }
}
