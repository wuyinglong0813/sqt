package com.tradepass.module.identity.service.fadada;

/** Internal lifecycle event; actor is audit evidence, never delegated provider authority. */
public record SigningMemberSyncRequested(Long companyId, Long targetUserId, Long actorUserId, String source) {}
