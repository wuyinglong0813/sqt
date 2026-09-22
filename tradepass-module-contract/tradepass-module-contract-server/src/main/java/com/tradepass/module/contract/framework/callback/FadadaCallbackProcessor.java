package com.tradepass.module.contract.framework.callback;
import com.tradepass.module.contract.dal.mysql.signing.FadadaCallbackEventMapper;
import com.tradepass.module.contract.dal.dataobject.signing.FadadaCallbackEventDO;

import com.tradepass.module.contract.service.signing.FadadaContractSigningService;
import com.tradepass.module.identity.api.fadada.FadadaCompanyOperations;
import com.tradepass.module.identity.api.fadada.FadadaCompanyOperations.*;
import com.tradepass.module.identity.api.fadada.FadadaPersonalIdentityOperations;
import com.tradepass.module.identity.api.fadada.FadadaPersonalIdentityOperations.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradepass.framework.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class FadadaCallbackProcessor {
    private static final Logger log = LoggerFactory.getLogger(FadadaCallbackProcessor.class);
    private final FadadaCallbackEventMapper eventMapper;
    private final FadadaPersonalIdentityOperations personalService;
    private final FadadaCompanyOperations companyService;
    private final FadadaContractSigningService signingService;
    private final ObjectMapper objectMapper;

    public FadadaCallbackProcessor(FadadaCallbackEventMapper eventMapper,
                                   FadadaPersonalIdentityOperations personalService,
                                   FadadaCompanyOperations companyService,
                                   FadadaContractSigningService signingService,
                                   ObjectMapper objectMapper) {
        this.eventMapper = eventMapper;
        this.personalService = personalService;
        this.companyService = companyService;
        this.signingService = signingService;
        this.objectMapper = objectMapper;
    }

    @Async
    public void processAsync(Long eventId) {
        process(eventId);
    }

    public void process(Long eventId) {
        LocalDateTime now = LocalDateTime.now();
        String token = java.util.UUID.randomUUID().toString();
        if (eventMapper.claim(eventId, token, now, now.plusMinutes(5)) != 1) return;
        FadadaCallbackEventDO event = eventMapper.selectById(eventId);
        if (event == null || !token.equals(event.getProcessingToken())) return;
        event.setFailureReason(null);
        event.setNextAttemptAt(null);
        try {
            JsonNode data = objectMapper.readTree(event.getRetryPayload());
            String clientUserId = text(data, "clientUserId");
            String clientCorpId = text(data, "clientCorpId");
            String openCorpId = text(data, "openCorpId");
            String signTaskId = text(data, "signTaskId");
            // Corporate authorization callbacks also carry the operator's clientUserId.
            // Prefer the most specific business identifier so they are not mistaken for
            // personal-identification callbacks.
            if (hasText(signTaskId)) {
                signingService.syncBySignTaskId(signTaskId);
                event.setSubjectType("CONTRACT");
                event.setStatus("PROCESSED");
            } else if (hasText(clientCorpId)) {
                var payload = companyService.syncCallback(clientCorpId, openCorpId, data);
                if (payload == null) throw new BusinessException("企业认证记录尚未就绪");
                event.setSubjectType("COMPANY");
                event.setSubjectId(Long.valueOf(payload.companyId()));
                event.setStatus("PROCESSED");
            } else if (hasText(openCorpId)) {
                var payload = companyService.syncCallback(null, openCorpId, data);
                if (payload == null) throw new BusinessException("企业认证记录尚未就绪");
                event.setSubjectType("COMPANY");
                event.setSubjectId(Long.valueOf(payload.companyId()));
                event.setStatus("PROCESSED");
            } else if (hasText(clientUserId)) {
                var payload = personalService.syncCallback(clientUserId, data);
                if (payload == null) throw new BusinessException("个人认证记录尚未就绪");
                event.setSubjectType("USER");
                event.setStatus("PROCESSED");
            } else {
                event.setStatus("IGNORED");
            }
        } catch (Exception exception) {
            log.warn("Electronic signature callback processing failed: eventId={}, attempt={}",
                    eventId, event.getAttemptCount(), exception);
            event.setStatus("FAILED");
            event.setFailureReason(shortMessage(exception));
            int attempts = event.getAttemptCount() == null ? 1 : Math.max(1, event.getAttemptCount());
            long delaySeconds = Math.min(3600L, 30L << Math.min(attempts - 1, 7));
            event.setNextAttemptAt(LocalDateTime.now().plusSeconds(delaySeconds));
        }
        event.setProcessedAt(LocalDateTime.now());
        eventMapper.finish(event, token);
    }

    private String text(JsonNode node, String name) {
        JsonNode value = node == null ? null : node.get(name);
        return value == null || value.isNull() ? null : value.asText();
    }
    private boolean hasText(String value) { return value != null && !value.isBlank(); }
    private String shortMessage(Exception exception) {
        String value = exception.getMessage();
        if (!hasText(value)) return "回调处理失败";
        return value.substring(0, Math.min(value.length(), 512));
    }
}
