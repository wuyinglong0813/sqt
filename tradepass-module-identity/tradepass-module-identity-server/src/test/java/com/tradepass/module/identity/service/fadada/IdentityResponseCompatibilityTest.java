package com.tradepass.module.identity.service.fadada;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradepass.module.identity.api.fadada.dto.FadadaCompanyIdentityRespDTO;
import com.tradepass.module.identity.api.fadada.dto.PersonalIdentityRespDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityResponseCompatibilityTest {
    @Test void responsesFromOlderIdentityDeploymentsDefaultToAResolvableCallback() throws Exception {
        var mapper = new ObjectMapper();
        var personal = mapper.readValue("{\"providerEnabled\":true,\"status\":\"IN_PROGRESS\"}", PersonalIdentityRespDTO.class);
        var corporate = mapper.readValue("{\"enabled\":true,\"companyId\":\"3\",\"status\":\"IN_PROGRESS\"}",
                FadadaCompanyIdentityRespDTO.class);
        assertThat(personal.callbackObsolete()).isFalse();
        assertThat(corporate.callbackObsolete()).isFalse();
        assertThat(mapper.readValue(mapper.writeValueAsString(personal.withCallbackObsolete(true)),
                PersonalIdentityRespDTO.class).callbackObsolete()).isTrue();
        assertThat(mapper.readValue(mapper.writeValueAsString(corporate.withCallbackObsolete(true)),
                FadadaCompanyIdentityRespDTO.class).callbackObsolete()).isTrue();
    }

    @Test void existingCallersCanUseAllPreviousConstructorShapes() {
        assertThat(new PersonalIdentityRespDTO(true, "IN_PROGRESS", null, null, null, null,
                null, null, null, null, null, null).callbackObsolete()).isFalse();
        assertThat(new FadadaCompanyIdentityRespDTO(true, "3", "IN_PROGRESS", null, null, null,
                null, 0, List.of(), null).callbackObsolete()).isFalse();
        assertThat(new FadadaCompanyIdentityRespDTO(true, "3", "IN_PROGRESS", null, null, null,
                null, 0, List.of(), null, "印章暂未同步").callbackObsolete()).isFalse();
    }
}
