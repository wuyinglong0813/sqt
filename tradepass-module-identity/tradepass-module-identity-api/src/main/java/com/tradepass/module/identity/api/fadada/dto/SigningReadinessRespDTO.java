package com.tradepass.module.identity.api.fadada.dto;

/** Local preparation hints for the current person; signing still performs its live contract preflight. */
public record SigningReadinessRespDTO(String status, String message, String action,
                                     boolean canManageSealAuthorization) {}
