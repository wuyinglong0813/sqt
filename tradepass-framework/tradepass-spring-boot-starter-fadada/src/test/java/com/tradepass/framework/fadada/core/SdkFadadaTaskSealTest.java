package com.tradepass.framework.fadada.core;

import com.fasc.open.api.bean.base.BaseRes;
import com.fasc.open.api.v5_1.client.SignTaskClient;
import com.fasc.open.api.v5_1.res.signtask.ListSignTaskFieldRes;
import com.tradepass.framework.fadada.config.FadadaProperties;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class SdkFadadaTaskSealTest {
    @Test void queriesTheActualActorSealAndChecksTaskIdentity() throws Exception {
        var properties = new FadadaProperties(); properties.setAppId("test"); properties.setAppSecret("test");
        properties.setServerUrl("https://example.test");
        var client = mock(SignTaskClient.class);
        var gateway = new SdkFadadaSigningGateway(properties, mock(FadadaAccessTokenProvider.class));
        ReflectionTestUtils.setField(gateway, "signTaskClient", client);
        var fields = new ListSignTaskFieldRes(); fields.setSignTaskId(123L);
        fields.setSignFields(List.of(field("supplier", "801"), field("buyer", "912"), field("supplier", "801")));
        when(client.listSignTaskField(any())).thenAnswer(call -> success(fields));
        assertThat(gateway.actorSealIds("123", "supplier")).containsExactly("801");
        fields.setSignTaskId(999L);
        assertThatThrownBy(() -> gateway.actorSealIds("123", "supplier")).hasMessageContaining("标识不一致");
        fields.setSignTaskId(123L); fields.setSignFields(List.of(field("supplier", null)));
        assertThatThrownBy(() -> gateway.actorSealIds("123", "supplier")).hasMessageContaining("未指定");
    }
    private ListSignTaskFieldRes.SignField field(String actor, String seal) {
        var field = new ListSignTaskFieldRes.SignField(); field.setActorId(actor); field.setSealId(seal);
        field.setFieldType("corp_seal"); return field;
    }
    @SuppressWarnings("unchecked") private <T> BaseRes<T> success(T data) {
        BaseRes<T> result = mock(BaseRes.class); when(result.isSuccess()).thenReturn(true); when(result.getData()).thenReturn(data);
        return result;
    }
}
