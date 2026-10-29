package com.tradepass.module.trade.service.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.module.identity.api.user.UserIdentityOperations;
import com.tradepass.module.trade.dal.dataobject.document.BusinessDocumentDO;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/** Keeps the draft author, issuer's reviewer, and counterparty signer separate. */
@Service
public class BusinessDocumentPeopleService {
    private final ObjectMapper objectMapper;
    private final UserIdentityOperations userIdentity;

    public BusinessDocumentPeopleService(ObjectMapper objectMapper, UserIdentityOperations userIdentity) {
        this.objectMapper = objectMapper;
        this.userIdentity = userIdentity;
    }

    public void recordReview(BusinessDocumentDO document, long companyId) {
        String verifiedName = userIdentity.requireCurrentVerifiedName(companyId);
        if (verifiedName == null || verifiedName.isBlank()) throw new BusinessException("请先完成个人实名认证");
        resolveNames(document);
        ObjectNode content = content(document);
        content.put("reviewedByName", verifiedName);
        content.put("reviewedByUserId", String.valueOf(AuthContext.userId()));
        content.put("reviewedByVerified", true);
        content.put("reviewedAt", LocalDateTime.now().toString());
        document.setContent(content.toString());
    }

    public void resolveNames(BusinessDocumentDO document) {
        ObjectNode content = content(document);
        // Old snapshots stored a WeChat nickname. Resolve the actual author instead of
        // attributing an old draft to its latest editor or publisher.
        if (!content.path("preparedByVerified").asBoolean(false)) {
            String name = document.getCreatedBy() == null ? "" : userIdentity.verifiedName(document.getCreatedBy());
            content.put("preparedByName", name == null ? "" : name);
        }
        // No issuer was recorded in old snapshots; acknowledgedBy belongs to the customer.
        if (!content.path("reviewedByVerified").asBoolean(false)) content.put("reviewedByName", "");
        document.setContent(content.toString());
    }

    private ObjectNode content(BusinessDocumentDO document) {
        try {
            if (objectMapper.readTree(document.getContent()) instanceof ObjectNode content) return content;
        } catch (Exception ignored) { }
        throw new BusinessException("单据内容格式不正确");
    }
}
