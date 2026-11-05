package com.tradepass.module.identity.controller.app.company;

import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.module.identity.dal.dataobject.company.CompanyInviteDO;
import com.tradepass.module.identity.dal.mysql.company.CompanyInviteMapper;
import com.tradepass.module.identity.service.auth.WechatService;
import com.tradepass.module.identity.service.permission.AccessControlService;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class CompanyInvitationMediaControllerTest {
    private final CompanyInviteMapper invites = mock(CompanyInviteMapper.class);
    private final AccessControlService access = mock(AccessControlService.class);
    private final WechatService wechat = mock(WechatService.class);
    private final CompanyInvitationMediaController controller = new CompanyInvitationMediaController(invites, access, wechat);

    private CompanyInviteDO invitation() {
        CompanyInviteDO invite = new CompanyInviteDO();
        invite.setCode("ABCD2345"); invite.setCompanyId(42L); invite.setType("member");
        invite.setUsed(false); invite.setExpiresAt(LocalDateTime.now().plusHours(1));
        when(invites.selectOne(any())).thenReturn(invite);
        return invite;
    }

    @Test
    void returnsQrImageOnlyAfterCheckingManagerOfTheInvitingCompany() {
        invitation();
        byte[] image = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        when(wechat.createInvitationCode("ABCD2345")).thenReturn(image);
        var result = controller.qrcode("ABCD2345").data();
        assertThat(result.mimeType()).isEqualTo("image/png");
        assertThat(Base64.getDecoder().decode(result.imageBase64())).isEqualTo(image);
        var order = inOrder(access, wechat);
        order.verify(access).requireManager(42L);
        order.verify(wechat).createInvitationCode("ABCD2345");
    }

    @Test
    void usesTheInvitationExpiryForTheHttpsLink() {
        CompanyInviteDO invite = invitation();
        long deadline = invite.getExpiresAt().atZone(ZoneId.systemDefault()).toEpochSecond();
        when(wechat.createInvitationLink("ABCD2345", deadline)).thenReturn("https://wxaurl.cn/example");
        assertThat(controller.shareLink("ABCD2345").data().url()).isEqualTo("https://wxaurl.cn/example");
        verify(access).requireManager(42L);
    }

    @Test
    void refusesUnauthorizedExpiredRevokedAndCooperationInvitations() {
        CompanyInviteDO invite = invitation();
        doThrow(new BusinessException("无权操作")).when(access).requireManager(42L);
        assertThatThrownBy(() -> controller.qrcode("ABCD2345")).hasMessage("无权操作");
        reset(access);
        invite.setUsed(true);
        assertThatThrownBy(() -> controller.shareLink("ABCD2345")).hasMessageContaining("失效");
        invite.setUsed(false); invite.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        assertThatThrownBy(() -> controller.qrcode("ABCD2345")).hasMessageContaining("失效");
        invite.setExpiresAt(LocalDateTime.now().plusHours(1)); invite.setType("counterparty");
        assertThatThrownBy(() -> controller.qrcode("ABCD2345")).hasMessageContaining("类型");
        verifyNoInteractions(wechat);
    }
}
