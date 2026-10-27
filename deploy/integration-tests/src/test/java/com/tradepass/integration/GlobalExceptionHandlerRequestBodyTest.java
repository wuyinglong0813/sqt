package com.tradepass.integration;

import com.tradepass.framework.web.core.handler.GlobalExceptionHandler;
import com.tradepass.module.contract.controller.app.membership.MembershipPaymentCallbackController;
import com.tradepass.module.contract.service.membership.MembershipPurchaseService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GlobalExceptionHandlerRequestBodyTest {
    @Test
    void anEmptyPaymentCallbackReturns400BeforeReachingPaymentProcessing() throws Exception {
        var purchases = mock(MembershipPurchaseService.class);
        mvc(new MembershipPaymentCallbackController(purchases))
                .perform(post("/api/membership/payment/notify").contentType(MediaType.APPLICATION_JSON).content(""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("请求内容缺失或格式不正确"));
        verifyNoInteractions(purchases);
    }

    @Test
    void malformedJsonIsAClientErrorWithoutEchoingPrivateRequestContent() throws Exception {
        mvc(new TypedBodyController())
                .perform(post("/test/request-body").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"private-value\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(content().string(not(containsString("private-value"))));
    }

    @Test
    void paymentSignatureRejectionRemainsAFailedCallback() throws Exception {
        var purchases = mock(MembershipPurchaseService.class);
        doThrow(new IllegalArgumentException("private-signature"))
                .when(purchases).notifyPayment(anyMap(), anyString());
        mvc(new MembershipPaymentCallbackController(purchases))
                .perform(post("/api/membership/payment/notify").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FAIL"))
                .andExpect(jsonPath("$.message").value("支付通知验证失败"));
        verify(purchases).notifyPayment(anyMap(), eq("{}"));
    }

    @Test
    void anUnavailablePaymentProcessorStillReturns500ForProviderRetry() throws Exception {
        var purchases = mock(MembershipPurchaseService.class);
        doThrow(new IllegalStateException("private-configuration"))
                .when(purchases).notifyPayment(anyMap(), anyString());
        mvc(new MembershipPaymentCallbackController(purchases))
                .perform(post("/api/membership/payment/notify").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("FAIL"))
                .andExpect(jsonPath("$.message").value("支付通知处理未完成"));
    }

    private MockMvc mvc(Object controller) {
        return MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @RestController
    static class TypedBodyController {
        record Body(String name) {}
        @PostMapping("/test/request-body")
        public Body receive(@RequestBody Body body) { return body; }
    }
}
