package com.tradepass.module.identity.api.fadada.dto;

public record PersonalIdentityRespDTO(
        boolean providerEnabled,
        String status,
        String statusText,
        String bindingStatus,
        String identStatus,
        String identProcessStatus,
        String verifiedName,
        String identMethod,
        String failureReason,
        String submittedAt,
        String verifiedAt,
        String lastSyncAt,
        boolean callbackObsolete
) {
    public PersonalIdentityRespDTO(boolean providerEnabled, String status, String statusText,
                                  String bindingStatus, String identStatus, String identProcessStatus,
                                  String verifiedName, String identMethod, String failureReason,
                                  String submittedAt, String verifiedAt, String lastSyncAt) {
        this(providerEnabled, status, statusText, bindingStatus, identStatus, identProcessStatus,
                verifiedName, identMethod, failureReason, submittedAt, verifiedAt, lastSyncAt, false);
    }

    public PersonalIdentityRespDTO withCallbackObsolete(boolean value) {
        return new PersonalIdentityRespDTO(providerEnabled, status, statusText, bindingStatus, identStatus,
                identProcessStatus, verifiedName, identMethod, failureReason, submittedAt, verifiedAt, lastSyncAt, value);
    }
}
