package com.tradepass.framework.common.pojo;

import java.math.BigDecimal;
import java.util.List;

public final class TradePassDtos {
    private TradePassDtos() {
    }

    public record UserProfile(String id, String openid, String phone, String nickname, String currentCompanyId, String currentRole) {
    }

    public record CompanyProfile(
            String id,
            String name,
            String creditCode,
            String legalPersonName,
            String registeredAddress,
            String contactPhone,
            String bankName,
            String bankAccount,
            String certificationStatus,
            String realNameStatus,
            String faceStatus,
            String sealStatus
    ) {
        public CompanyProfile(String id, String name, String creditCode, String legalPersonName,
                              String certificationStatus, String realNameStatus, String faceStatus, String sealStatus) {
            this(id, name, creditCode, legalPersonName, null, null, null, null,
                    certificationStatus, realNameStatus, faceStatus, sealStatus);
        }
    }

    /** 企业搜索的最小响应，不包含法人、联系方式、开户地址或银行账号。 */
    public record CompanySearchSummary(
            String id,
            String name,
            String maskedCreditCode,
            boolean verified
    ) {
    }

    /** 公司成员信息（角色 + 权限点 + 状态） */
    public record MemberInfo(String userId, String userName, String phone, String roleCode, String roleText, List<String> permissions, String memberStatus) {
    }

    public record LoginSession(String token, UserProfile user) {
    }

    /** 用户在某家公司的角色 */
    public record CompanyRole(String companyId, String companyName, String roleCode, String roleText) {
    }

    /** /api/me 返回：用户 + 当前企业 + 成员角色 + 所有企业列表 */
    public record MePayload(UserProfile user, CompanyProfile company, MemberInfo member, List<CompanyRole> companies) {
    }

    public record RankingItem(int rank, String counterpartyName, BigDecimal amount, int orderCount, String trend) {
    }

    public record HomePayload(String companyId, String companyName, String role, String roleText, List<String> periods, List<RankingItem> ranking) {
    }

    public record SealRecord(String id, String companyId, String fileUrl, String usage, String status) {
    }

    public record MemberRole(String code, String name) {
    }

    public record AuthorizationRecord(String id, String companyId, String userId, String memberName, String roleCode, String roleText, List<String> permissions, String status, String phone, Boolean realNameVerified, List<MemberRole> roles) {
    }

    /** dev 用户切换列表项 */
    public record DevUser(String id, String name, String phone, String roleCode, String roleText) {
    }

    public record UploadTokenPayload(String objectKey, String uploadUrl, String token, String expiresAt) {
    }

    public record CounterpartyRelation(String id, String counterpartyCompanyId, String counterpartyName,
                                       String relationType, String status) {
    }
}
