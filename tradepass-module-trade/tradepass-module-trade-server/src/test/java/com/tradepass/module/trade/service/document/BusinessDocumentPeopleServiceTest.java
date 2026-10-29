package com.tradepass.module.trade.service.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.module.identity.api.user.UserIdentityOperations;
import com.tradepass.module.trade.dal.dataobject.document.BusinessDocumentDO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BusinessDocumentPeopleServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final UserIdentityOperations identities = mock(UserIdentityOperations.class);
    private final BusinessDocumentPeopleService service = new BusinessDocumentPeopleService(mapper, identities);

    @AfterEach void clearContext() { AuthContext.clear(); }

    @Test void publisherIsReviewerAndDoesNotReplaceOriginalDraftAuthor() throws Exception {
        AuthContext.set(9L, 3L);
        when(identities.requireCurrentVerifiedName(3L)).thenReturn("张审核");
        BusinessDocumentDO document = new BusinessDocumentDO();
        document.setCreatedBy(7L);
        document.setContent("{\"preparedByName\":\"李制单\",\"preparedByVerified\":true,\"preparedByUserId\":\"7\"}");

        service.recordReview(document, 3L);

        var content = mapper.readTree(document.getContent());
        assertThat(document.getCreatedBy()).isEqualTo(7L);
        assertThat(content.path("preparedByName").asText()).isEqualTo("李制单");
        assertThat(content.path("preparedByUserId").asText()).isEqualTo("7");
        assertThat(content.path("reviewedByName").asText()).isEqualTo("张审核");
        assertThat(content.path("reviewedByUserId").asText()).isEqualTo("9");
        assertThat(content.path("reviewedAt").asText()).isNotBlank();
        verify(identities, never()).verifiedName(anyLong());
    }

    @Test void legacyNicknameResolvesOriginalCreatorsRealNameAndDoesNotInventReviewer() throws Exception {
        when(identities.verifiedName(7L)).thenReturn("李实名");
        BusinessDocumentDO document = new BusinessDocumentDO();
        document.setCreatedBy(7L);
        document.setAcknowledgedBy(8L);
        document.setContent("{\"preparedByName\":\"微信用户\",\"reviewedByName\":\"客户姓名\"}");

        service.resolveNames(document);

        var content = mapper.readTree(document.getContent());
        assertThat(content.path("preparedByName").asText()).isEqualTo("李实名");
        assertThat(content.path("reviewedByName").asText()).isEmpty();
        verify(identities).verifiedName(7L);
        verify(identities, never()).verifiedName(8L);
    }

    @Test void unverifiedLegacyAuthorIsShownWithoutNickname() throws Exception {
        when(identities.verifiedName(7L)).thenReturn("");
        BusinessDocumentDO document = new BusinessDocumentDO();
        document.setCreatedBy(7L);
        document.setContent("{\"preparedByName\":\"微信用户\"}");
        service.resolveNames(document);
        assertThat(mapper.readTree(document.getContent()).path("preparedByName").asText()).isEmpty();
    }
}
