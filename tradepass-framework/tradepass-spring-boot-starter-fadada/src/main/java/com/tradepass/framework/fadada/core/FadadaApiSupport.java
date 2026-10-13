package com.tradepass.framework.fadada.core;

import com.fasc.open.api.bean.base.BaseReq;
import com.fasc.open.api.bean.base.BaseRes;
import com.fasc.open.api.exception.ApiException;

/** Only an explicit expired-token rejection is safe to replay, including mutations. */
final class FadadaApiSupport {
    private FadadaApiSupport() {}

    static <T> BaseRes<T> execute(BaseReq request, FadadaAccessTokenProvider tokens, ApiCall<T> call)
            throws ApiException {
        BaseRes<T> response = call.call();
        if (response != null && !response.isSuccess() && "100002".equals(response.getCode())) {
            tokens.invalidate(request.getAccessToken());
            request.setAccessToken(tokens.get());
            return call.call();
        }
        return response;
    }

    @FunctionalInterface
    interface ApiCall<T> { BaseRes<T> call() throws ApiException; }
}
