package com.tradepass.module.contract.service.signing;

/** Describes confirmed party progress without changing the contract lifecycle. */
final class ContractSigningProgress {
    private ContractSigningProgress() {}

    static boolean signed(String status) {
        return "signed".equalsIgnoreCase(status) || "sign_completed".equalsIgnoreCase(status)
                || "completed".equalsIgnoreCase(status);
    }

    static String partyText(String providerStatus, String ownStatus, String otherStatus, boolean initiator) {
        if (!"sign_progress".equalsIgnoreCase(providerStatus) && !"finish_creation".equalsIgnoreCase(providerStatus)
                && !"WAITING_AUTH".equalsIgnoreCase(providerStatus)) {
            return null;
        }
        if (signed(ownStatus) && signed(otherStatus)) return "双方已签署，正在归档";
        if (signed(ownStatus)) return "待对方签署";
        if (signed(otherStatus)) return "待我方签署";
        // The signing entry point enforces that the initiator signs first.
        return initiator ? "待我方签署" : "待对方签署";
    }
}
