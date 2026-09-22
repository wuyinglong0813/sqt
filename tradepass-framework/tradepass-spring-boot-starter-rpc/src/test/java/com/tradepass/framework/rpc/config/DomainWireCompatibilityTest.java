package com.tradepass.framework.rpc.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradepass.module.settlement.api.attachment.AttachmentApprovalOperations.PendingAttachment;
import feign.Request;
import feign.Response;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DomainWireCompatibilityTest {
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test void monetaryMapsKeepDecimalPrecisionAndScaleAcrossHttp() throws Exception {
        var decoder = new DomainFeignConfiguration().domainDecoder(mapper);
        var value = (Map<?, ?>) decoder.decode(response(200, "{\"amount\":12345678901234567890.12,\"zero\":0.00}"),
                new TypeReference<Map<String, Object>>() { }.getType());
        assertEquals(new BigDecimal("12345678901234567890.12"), value.get("amount"));
        assertEquals(new BigDecimal("0.00"), value.get("zero"));
    }

    @Test void pendingItemsKeepTypedTimeForOriginalStableSorting() throws Exception {
        var decoder = new DomainFeignConfiguration().domainDecoder(mapper);
        var values = (List<?>) decoder.decode(response(200, "[{\"view\":{\"amount\":0.10},\"createdAt\":\"2026-09-16T12:30:00\"}]"),
                new TypeReference<List<PendingAttachment>>() { }.getType());
        var value = (PendingAttachment) values.get(0);
        assertEquals(LocalDateTime.of(2026, 9, 16, 12, 30), value.createdAt());
        assertEquals(new BigDecimal("0.10"), value.view().get("amount"));
    }

    @Test void businessFailureKeepsItsOriginalMessageButInfrastructureDoesNotLeakInternals() {
        var decoder = new DomainFeignConfiguration().domainErrors(mapper);
        var business = decoder.decode("test", response(400, "{\"message\":\"原业务校验失败\"}"));
        assertInstanceOf(com.tradepass.framework.common.exception.BusinessException.class, business);
        assertEquals("原业务校验失败", business.getMessage());
        var unavailable = decoder.decode("test", response(500, "{\"message\":\"private database detail\"}"));
        assertInstanceOf(DomainFeignConfiguration.DomainUnavailableException.class, unavailable);
        assertFalse(unavailable.getMessage().contains("private"));
    }

    static Response response(int status, String body) {
        return Response.builder().status(status).reason("test")
                .request(Request.create(Request.HttpMethod.POST, "http://isolated/internal/domain/test", Map.of(), null, StandardCharsets.UTF_8, null))
                .headers(Map.of("Content-Type", List.of("application/json")))
                .body(body, StandardCharsets.UTF_8).build();
    }
}
