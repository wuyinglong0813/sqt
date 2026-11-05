package com.tradepass.module.identity.controller.app.company;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.common.pojo.ApiResponse;
import com.tradepass.module.identity.dal.dataobject.company.CompanyInviteDO;
import com.tradepass.module.identity.dal.mysql.company.CompanyInviteMapper;
import com.tradepass.module.identity.service.auth.WechatService;
import com.tradepass.module.identity.service.permission.AccessControlService;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;

@RestController
@RequestMapping("/api/companies/invitations")
public class CompanyInvitationMediaController {
    private final CompanyInviteMapper invites;
    private final AccessControlService access;
    private final WechatService wechat;

    public CompanyInvitationMediaController(CompanyInviteMapper invites, AccessControlService access, WechatService wechat) {
        this.invites = invites;
        this.access = access;
        this.wechat = wechat;
    }

    public record CodeImage(String imageBase64, String mimeType) {}
    public record ShareLink(String url) {}

    @GetMapping("/{code}/qrcode")
    public ApiResponse<CodeImage> qrcode(@PathVariable String code) {
        requireManagedInvitation(code);
        byte[] image = wechat.createInvitationCode(code);
        if (image == null || image.length < 3) throw new BusinessException("微信小程序码生成失败，请重试");
        String mimeType = image[0] == (byte) 0x89 ? "image/png" : "image/jpeg";
        return ApiResponse.ok(new CodeImage(Base64.getEncoder().encodeToString(image), mimeType));
    }

    @PostMapping("/{code}/share-link")
    public ApiResponse<ShareLink> shareLink(@PathVariable String code) {
        CompanyInviteDO invite = requireManagedInvitation(code);
        long expiresAt = invite.getExpiresAt().atZone(ZoneId.systemDefault()).toEpochSecond();
        return ApiResponse.ok(new ShareLink(wechat.createInvitationLink(code, expiresAt)));
    }

    private CompanyInviteDO requireManagedInvitation(String code) {
        if (code == null || !code.matches("[A-Z2-9]{8}")) throw new BusinessException("邀请码格式无效");
        CompanyInviteDO invite = invites.selectOne(new LambdaQueryWrapper<CompanyInviteDO>()
                .eq(CompanyInviteDO::getCode, code).last("LIMIT 1"));
        if (invite == null) throw new BusinessException("邀请码无效");
        access.requireManager(invite.getCompanyId());
        if (!"member".equals(invite.getType())) throw new BusinessException("此邀请类型不支持成员邀请分享");
        if (Boolean.TRUE.equals(invite.getUsed()) || invite.getExpiresAt() == null
                || !invite.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw new BusinessException("邀请已失效，请重新生成");
        }
        return invite;
    }
}
