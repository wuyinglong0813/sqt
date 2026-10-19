package com.tradepass.framework.fadada.core;

import com.fasc.open.api.bean.base.BaseReq;
import com.fasc.open.api.bean.base.BaseRes;
import com.fasc.open.api.exception.ApiException;
import com.fasc.open.api.v5_1.client.OpenApiClient;
import com.fasc.open.api.v5_1.client.SealClient;
import com.fasc.open.api.v5_1.req.seal.GetSealGrantUrlReq;
import com.fasc.open.api.v5_1.req.seal.GetUserSealListReq;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.fadada.config.FadadaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.HashSet;
import java.util.List;

@Component
public class SdkFadadaSealAuthorizationGateway implements FadadaSealAuthorizationGateway {
    private static final Logger log = LoggerFactory.getLogger(SdkFadadaSealAuthorizationGateway.class);
    private final SealClient sealClient;
    private final FadadaAccessTokenProvider tokens;

    public SdkFadadaSealAuthorizationGateway(FadadaProperties properties, FadadaAccessTokenProvider tokens) {
        this.tokens = tokens;
        this.sealClient = new SealClient(new OpenApiClient(
                properties.getAppId(), properties.getAppSecret(), properties.getServerUrl()));
    }

    @Override
    public List<MemberSealGrant> memberSealGrants(String openCorpId, long memberId) {
        requireMember(openCorpId, memberId);
        var request = new GetUserSealListReq();
        request.setAccessToken(tokens.get());
        request.setOpenCorpId(openCorpId);
        request.setMemberId(memberId);
        var response = invoke(request, () -> sealClient.getUserSealList(request), "查询成员用印权限");
        var seals = response.getSealInfos();
        if (seals == null) throw new BusinessException("成员用印权限查询结果不完整，请稍后重试");
        var seen = new HashSet<Long>();
        return seals.stream().map(seal -> {
            if (seal == null || seal.getSealId() == null || seal.getSealId() <= 0 || !seen.add(seal.getSealId())
                    || seal.getSealStatus() == null || seal.getGrantStatus() == null) {
                throw new BusinessException("成员用印权限查询结果不完整，请稍后重试");
            }
            return new MemberSealGrant(String.valueOf(seal.getSealId()), seal.getSealStatus(), seal.getGrantStatus());
        }).toList();
    }

    @Override
    public String memberSealGrantUrl(String openCorpId, long sealId, long memberId, String clientUserId) {
        requireMember(openCorpId, memberId);
        if (sealId <= 0 || clientUserId == null || clientUserId.isBlank()) {
            throw new BusinessException("用印授权参数不完整");
        }
        var request = new GetSealGrantUrlReq();
        request.setAccessToken(tokens.get());
        request.setOpenCorpId(openCorpId);
        request.setSealId(sealId);
        request.setClientUserId(clientUserId);
        var memberInfo = new GetSealGrantUrlReq.MemberInfo();
        memberInfo.setMemberIds(List.of(memberId));
        request.setMemberInfo(memberInfo);
        // redirectUrl is a webpage URL, not a native mini-program path. The provider
        // confirms the grant; returning from the editor alone never marks it granted.
        var response = invoke(request, () -> sealClient.getSealGrantUrl(request), "获取成员用印授权地址");
        String url = response.getSealGrantUrl();
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException invalid) {
            throw new BusinessException("未获取到有效的成员用印授权地址，请稍后重试");
        }
        return url;
    }

    private void requireMember(String openCorpId, long memberId) {
        if (openCorpId == null || openCorpId.isBlank() || memberId <= 0) {
            throw new BusinessException("企业成员授权参数不完整");
        }
    }

    private <T> T invoke(BaseReq request, FadadaApiSupport.ApiCall<T> call, String action) {
        try {
            BaseRes<T> response = FadadaApiSupport.execute(request, tokens, call);
            if (response == null || !response.isSuccess() || response.getData() == null) {
                log.warn("Electronic signature {} failed: code={}, requestId={}", action,
                        response == null ? "null" : response.getCode(),
                        response == null ? "" : response.getRequestId());
                throw new BusinessException(action + "失败，请稍后重试");
            }
            return response.getData();
        } catch (ApiException exception) {
            // Do not log account details, provider tickets or the full request body.
            log.warn("Electronic signature {} request failed", action);
            throw new BusinessException(action + "失败，请稍后重试");
        }
    }
}
