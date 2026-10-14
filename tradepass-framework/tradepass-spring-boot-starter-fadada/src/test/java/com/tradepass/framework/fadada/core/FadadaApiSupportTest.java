package com.tradepass.framework.fadada.core;

import com.fasc.open.api.bean.base.BaseReq;
import com.fasc.open.api.bean.base.BaseRes;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.concurrent.atomic.AtomicInteger;

class FadadaApiSupportTest {
    @Test void respectsShortTokenLifetimesInsteadOfExtendingThem() {
        assertThat(FadadaAccessTokenProvider.cacheLifetime(30)).isEqualTo(27);
        assertThat(FadadaAccessTokenProvider.cacheLifetime(1)).isZero();
        assertThat(FadadaAccessTokenProvider.cacheLifetime(7200)).isEqualTo(7140);
        assertThatThrownBy(() -> FadadaAccessTokenProvider.cacheLifetime(0)).hasMessageContaining("有效期异常");
    }
    @Test void retriesExactlyOnceAfterAnExplicitExpiredTokenRejection() throws Exception {
        var request = new BaseReq(); request.setAccessToken("old-token");
        var tokens = mock(FadadaAccessTokenProvider.class); when(tokens.get()).thenReturn("new-token");
        var calls = new AtomicInteger();
        var expired = response("100002", false); var success = response("100000", true);
        assertThat(FadadaApiSupport.execute(request, tokens, () -> calls.getAndIncrement() == 0 ? expired : success))
                .isSameAs(success);
        assertThat(request.getAccessToken()).isEqualTo("new-token");
        assertThat(calls.get()).isEqualTo(2); verify(tokens).invalidate("old-token");
    }
    @Test void aSecondExpiredTokenFailureCannotLoop() throws Exception {
        var request = new BaseReq(); request.setAccessToken("old-token");
        var tokens = mock(FadadaAccessTokenProvider.class); when(tokens.get()).thenReturn("new-token");
        var calls = new AtomicInteger(); var expired = response("100002", false);
        assertThat(FadadaApiSupport.execute(request, tokens, () -> { calls.incrementAndGet(); return expired; }))
                .isSameAs(expired);
        assertThat(calls.get()).isEqualTo(2);
    }
    @Test void invalidatesTheRejectedTokenEvenWhenTheOfficialSdkClearsItFromTheRequest() throws Exception {
        var request = new BaseReq(); request.setAccessToken("old-token");
        var tokens = mock(FadadaAccessTokenProvider.class); when(tokens.get()).thenReturn("new-token");
        var usedTokens = new java.util.ArrayList<String>();
        var expired = response("100002", false); var success = response("100000", true);
        assertThat(FadadaApiSupport.execute(request, tokens, () -> {
            usedTokens.add(request.getAccessToken());
            // OpenApiClient.getBodyMap clears this field before serializing bizContent.
            request.setAccessToken(null);
            return usedTokens.size() == 1 ? expired : success;
        })).isSameAs(success);
        assertThat(usedTokens).containsExactly("old-token", "new-token");
        verify(tokens).invalidate("old-token");
    }
    @Test void doesNotReplayMutationsOnGenericRateLimitSignatureOrUnknownErrors() throws Exception {
        for (String code : java.util.List.of("100001", "100003", "100004", "100009", "210002")) {
            var request = new BaseReq(); var tokens = mock(FadadaAccessTokenProvider.class);
            var calls = new AtomicInteger(); var failed = response(code, false);
            assertThat(FadadaApiSupport.execute(request, tokens, () -> { calls.incrementAndGet(); return failed; }))
                    .isSameAs(failed);
            assertThat(calls.get()).isEqualTo(1); verifyNoInteractions(tokens);
        }
    }
    @Test void cannotInvalidateATokenRefreshedByAnotherRequest() {
        var properties = new com.tradepass.framework.fadada.config.FadadaProperties();
        properties.setAppId("test"); properties.setAppSecret("test"); properties.setServerUrl("https://example.test");
        var tokens = new FadadaAccessTokenProvider(properties);
        var until = java.time.Instant.now().plusSeconds(60);
        org.springframework.test.util.ReflectionTestUtils.setField(tokens, "accessToken", "fresh-token");
        org.springframework.test.util.ReflectionTestUtils.setField(tokens, "expiresAt", until);
        tokens.invalidate("older-token");
        assertThat(tokens.get()).isEqualTo("fresh-token");
        tokens.invalidate("fresh-token");
        assertThat(org.springframework.test.util.ReflectionTestUtils.getField(tokens, "expiresAt"))
                .isEqualTo(java.time.Instant.EPOCH);
    }
    @SuppressWarnings("unchecked")
    private BaseRes<Object> response(String code, boolean success) {
        var response = (BaseRes<Object>) mock(BaseRes.class);
        when(response.getCode()).thenReturn(code); when(response.isSuccess()).thenReturn(success);
        return response;
    }
}
