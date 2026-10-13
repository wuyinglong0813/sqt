package com.tradepass.framework.fadada.core;

import com.fasc.open.api.v5_1.res.signtask.SignTaskActorGetUrlRes;
import com.tradepass.framework.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SdkFadadaSigningGatewayTest {

    @Test
    void signingReturnUrlIsEncodedOnceAndUsesTheEmbedAddress() throws Exception {
        var f = new GatewayFixture();
        var url = new SignTaskActorGetUrlRes(); url.setActorSignTaskEmbedUrl("https://example.test/embed");
        org.mockito.Mockito.when(f.client.signTaskActorGetUrl(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            var request = (com.fasc.open.api.v5_1.req.signtask.SignTaskActorGetUrlReq) call.getArgument(0);
            assertThat(java.net.URLDecoder.decode(request.getRedirectMiniAppUrl(), java.nio.charset.StandardCharsets.UTF_8))
                    .isEqualTo("/pages/service-return/service-return?scene=contract&contractId=12");
            assertThat(request.getRedirectMiniAppUrl()).startsWith("%2Fpages%2F");
            assertThat(request.getActorId()).isEqualTo("supplier"); return f.successful(url);
        });
        assertThat(f.gateway.actorUrl("task-12", "supplier", "user-7",
                "/pages/service-return/service-return?scene=contract&contractId=12")).isEqualTo("https://example.test/embed");
    }

    @Test
    void queriesTheCurrentDocumentedDetailEndpointAndChecksTheReturnedTaskIdentity() throws Exception {
        var f = new GatewayFixture();
        var detail = new com.fasc.open.api.v5_1.res.signtask.SignTaskDetailRes();
        detail.setSignTaskId("task-12"); detail.setSignTaskStatus("sign_progress");
        org.mockito.Mockito.when(f.client.querySignTaskDetail(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> f.successful(detail));
        var actor = new com.fasc.open.api.v5_1.res.signtask.ListSignTaskActorRes();
        actor.setActorId("supplier"); actor.setSignStatus("signed");
        org.mockito.Mockito.when(f.client.listSignTaskActor(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> f.successful(java.util.List.of(actor)));
        assertThat(f.gateway.status("task-12").actors()).containsExactly(new FadadaSigningGateway.ActorStatus("supplier", "signed"));
        org.mockito.Mockito.verify(f.client, org.mockito.Mockito.never()).getDetail(org.mockito.ArgumentMatchers.any());
        detail.setSignTaskId("other-task");
        assertThatThrownBy(() -> f.gateway.status("task-12")).hasMessageContaining("主体不一致");
    }

    @Test
    void rejectsDuplicateActorsAndInvalidSourceNamesInsteadOfConfirmingOrSendingThem() throws Exception {
        var f = new GatewayFixture();
        var detail = new com.fasc.open.api.v5_1.res.signtask.SignTaskDetailRes();
        detail.setSignTaskId("task-12"); detail.setSignTaskStatus("task_finished");
        org.mockito.Mockito.when(f.client.querySignTaskDetail(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> f.successful(detail));
        var actor = new com.fasc.open.api.v5_1.res.signtask.ListSignTaskActorRes(); actor.setActorId("supplier");
        org.mockito.Mockito.when(f.client.listSignTaskActor(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> f.successful(java.util.List.of(actor, actor)));
        assertThatThrownBy(() -> f.gateway.status("task-12")).hasMessageContaining("参与方结果不完整");
        var invalid = new FadadaSigningGateway.CreateTaskCommand(new byte[]{1}, "bad/name.pdf", "合同", "ref",
                "corp-1", "supplier", "供方", "1", "corp-2", "buyer", "需方", "2", "supplier", "buyer", null);
        assertThatThrownBy(() -> f.gateway.createTask(invalid)).hasMessageContaining("有效的PDF文件名");
        org.mockito.Mockito.verify(f.client, org.mockito.Mockito.never()).create(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void abolishRequestNamesTheOriginalActorAndThePlatformProtocolSource() throws Exception {
        var f = new GatewayFixture();
        var response = new com.fasc.open.api.v5_1.res.signtask.CancelSignTaskCreateRes();
        response.setAbolishedSignTaskId("child-task");
        org.mockito.Mockito.when(f.client.abolishSignTask(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            var request = (com.fasc.open.api.v5_1.req.signtask.CancelSignTaskCreateReq) call.getArgument(0);
            assertThat(request.getAbolishedInitiator().getActorId()).isEqualTo("buyer");
            assertThat(request.getAbolishedInitiator().getInitiatorId()).isNull();
            assertThat(request.getDocSource()).isEqualTo("platform");
            assertThat(request.getFollowOriginalConfig()).isTrue(); assertThat(request.getAutoStart()).isTrue();
            return f.successful(response);
        });
        assertThat(f.gateway.abolish("original-task", "buyer", "双方协商", "https://example.test/callback"))
                .isEqualTo("child-task");
        assertThatThrownBy(() -> f.gateway.abolish("original-task", "buyer", "字".repeat(201), null))
                .hasMessageContaining("200字");
        org.mockito.Mockito.verify(f.client, org.mockito.Mockito.times(1)).abolishSignTask(org.mockito.ArgumentMatchers.any());
    }

    private static class GatewayFixture {
        final SdkFadadaSigningGateway gateway;
        final com.fasc.open.api.v5_1.client.SignTaskClient client = org.mockito.Mockito.mock(com.fasc.open.api.v5_1.client.SignTaskClient.class);
        GatewayFixture() {
            var properties = new com.tradepass.framework.fadada.config.FadadaProperties(); properties.setAppId("test");
            properties.setAppSecret("test"); properties.setServerUrl("https://example.test");
            gateway = new SdkFadadaSigningGateway(properties, org.mockito.Mockito.mock(FadadaAccessTokenProvider.class));
            org.springframework.test.util.ReflectionTestUtils.setField(gateway, "signTaskClient", client);
        }
        @SuppressWarnings("unchecked")
        <T> com.fasc.open.api.bean.base.BaseRes<T> successful(T data) {
            var response = (com.fasc.open.api.bean.base.BaseRes<T>) org.mockito.Mockito.mock(com.fasc.open.api.bean.base.BaseRes.class);
            org.mockito.Mockito.when(response.isSuccess()).thenReturn(true); org.mockito.Mockito.when(response.getData()).thenReturn(data);
            return response;
        }
    }

    @Test
    void usesMiniProgramEmbedUrlInsteadOfDistributedShortUrl() {
        SignTaskActorGetUrlRes response = new SignTaskActorGetUrlRes();
        response.setActorSignTaskUrl("https://test.fdd9.cn/short-link");
        response.setActorSignTaskEmbedUrl("https://80005620.uat-e.fadada.com/connect?ticket=one-time");

        assertThat(SdkFadadaSigningGateway.requiredActorEmbedUrl(response))
                .isEqualTo("https://80005620.uat-e.fadada.com/connect?ticket=one-time");
    }

    @Test
    void doesNotFallBackToShortUrlWhenEmbedUrlIsMissing() {
        SignTaskActorGetUrlRes response = new SignTaskActorGetUrlRes();
        response.setActorSignTaskUrl("https://test.fdd9.cn/short-link");

        assertThatThrownBy(() -> SdkFadadaSigningGateway.requiredActorEmbedUrl(response))
                .isInstanceOf(BusinessException.class)
                .hasMessage("未获取到小程序合同签署地址，请稍后重试");
    }

    @Test
    void selectsTheLastSignedContractPageFromProviderImageArchive() throws Exception {
        byte[] firstPage = png((byte) 1);
        byte[] lastPage = png((byte) 9);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            add(zip, "contract/page-1.png", firstPage);
            add(zip, "contract/page-10.png", lastPage);
            add(zip, "contract/readme.txt", "ignored".getBytes());
        }

        assertThat(SdkFadadaSigningGateway.lastPngFromZip(output.toByteArray()))
                .containsExactly(lastPage);
    }

    private byte[] png(byte marker) {
        return new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, marker};
    }

    private void add(ZipOutputStream zip, String name, byte[] data) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }
}
