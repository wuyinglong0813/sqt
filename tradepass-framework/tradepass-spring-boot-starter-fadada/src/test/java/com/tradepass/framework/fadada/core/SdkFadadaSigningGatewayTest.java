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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"supplier", "buyer"})
    void usesActualPdfCoordinatesAndKeepsSupplierBuyerFieldsWhenTheBuyerInitiates(String initiator) throws Exception {
        var f = new GatewayFixture(); f.prepareFile(200);
        var response = new com.fasc.open.api.v5_1.res.signtask.CreateSignTaskRes(); response.setSignTaskId("task-new");
        org.mockito.Mockito.when(f.client.create(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            var request = (com.fasc.open.api.v5_1.req.signtask.CreateSignTaskReq) call.getArgument(0);
            var fields = request.getDocs().get(0).getDocFields();
            assertThat(fields).hasSize(2);
            assertThat(fields.get(0).getFieldId()).isEqualTo("supplier-seal");
            assertThat(fields.get(0).getPosition().getPositionMode()).isEqualTo("pixel");
            assertThat(fields.get(0).getPosition().getPositionPageNo()).isEqualTo(3);
            assertThat(fields.get(0).getPosition().getPositionX()).isEqualTo("220");
            assertThat(fields.get(0).getPosition().getPositionY()).isEqualTo("600");
            assertThat(fields.get(0).getPosition().getPositionKeyword()).isNull();
            assertThat(fields.get(1).getFieldId()).isEqualTo("buyer-seal");
            assertThat(fields.get(1).getPosition().getPositionX()).isEqualTo("580");
            assertThat(fields.get(1).getPosition().getPositionPageNo()).isEqualTo(3);
            assertThat(request.getActors().get(0).getActor().getActorId()).isEqualTo(initiator);
            assertThat(request.getActors().get(0).getSignFields().get(0).getFieldId()).isEqualTo(initiator + "-seal");
            assertThat(request.getActors().get(0).getSignConfigInfo().getOrderNo()).isEqualTo(1);
            assertThat(request.getActors().get(1).getSignFields().get(0).getFieldId())
                    .isEqualTo(("supplier".equals(initiator) ? "buyer" : "supplier") + "-seal");
            assertThat(request.getActors().get(1).getSignConfigInfo().getOrderNo()).isEqualTo(2);
            return f.successful(response);
        });
        assertThat(f.gateway.createTask(command(initiator)).signTaskId()).isEqualTo("task-new");
    }

    @Test
    void uploadFailureIsProvenToOccurBeforeAnyTaskCreation() throws Exception {
        var f = new GatewayFixture(); f.prepareFile(503);
        assertThatThrownBy(() -> f.gateway.createTask(command("supplier")))
                .isInstanceOf(FadadaTaskPreparationException.class).hasMessageContaining("上传失败");
        org.mockito.Mockito.verify(f.docs, org.mockito.Mockito.never()).process(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(f.client, org.mockito.Mockito.never()).create(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void fileProcessingFailureIsProvenToOccurBeforeAnyTaskCreation() throws Exception {
        var f = new GatewayFixture(); f.prepareFile(200);
        org.mockito.Mockito.when(f.docs.process(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new BusinessException("文件处理暂不可用"));
        assertThatThrownBy(() -> f.gateway.createTask(command("supplier")))
                .isInstanceOf(FadadaTaskPreparationException.class).hasMessageContaining("文件处理暂不可用");
        org.mockito.Mockito.verify(f.client, org.mockito.Mockito.never()).create(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void actualCreationTimeoutIsNeverMisclassifiedAsSafePreparationFailure() throws Exception {
        var f = new GatewayFixture(); f.prepareFile(200);
        org.mockito.Mockito.when(f.client.create(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new BusinessException("task create timed out"));
        assertThatThrownBy(() -> f.gateway.createTask(command("supplier")))
                .isInstanceOf(BusinessException.class).isNotInstanceOf(FadadaTaskPreparationException.class);
        org.mockito.Mockito.verify(f.client).create(org.mockito.ArgumentMatchers.any());
    }

    private FadadaSigningGateway.CreateTaskCommand command(String initiator) {
        String other = "supplier".equals(initiator) ? "buyer" : "supplier";
        return new FadadaSigningGateway.CreateTaskCommand(new byte[]{1}, "合同.pdf", "合同", "ref",
                "corp-1", initiator, "本方", "1", "corp-2", other, "对方", "2", "supplier", "buyer", null,
                new FadadaSigningGateway.SealPosition(3, "220", "600"),
                new FadadaSigningGateway.SealPosition(3, "580", "600"));
    }

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
        final com.fasc.open.api.v5_1.client.DocClient docs = org.mockito.Mockito.mock(com.fasc.open.api.v5_1.client.DocClient.class);
        final java.net.http.HttpClient http = org.mockito.Mockito.mock(java.net.http.HttpClient.class);
        GatewayFixture() {
            var properties = new com.tradepass.framework.fadada.config.FadadaProperties(); properties.setAppId("test");
            properties.setAppSecret("test"); properties.setServerUrl("https://example.test");
            gateway = new SdkFadadaSigningGateway(properties, org.mockito.Mockito.mock(FadadaAccessTokenProvider.class));
            org.springframework.test.util.ReflectionTestUtils.setField(gateway, "signTaskClient", client);
            org.springframework.test.util.ReflectionTestUtils.setField(gateway, "docClient", docs);
            org.springframework.test.util.ReflectionTestUtils.setField(gateway, "httpClient", http);
        }
        @SuppressWarnings("unchecked")
        void prepareFile(int status) throws Exception {
            var upload = org.mockito.Mockito.mock(com.fasc.open.api.v5_1.res.doc.GetUploadUrlRes.class);
            org.mockito.Mockito.when(upload.getUploadUrl()).thenReturn("https://example.test/upload");
            org.mockito.Mockito.when(upload.getFddFileUrl()).thenReturn("fdd-file");
            org.mockito.Mockito.when(docs.getUploadFileUrl(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> successful(upload));
            var uploaded = (java.net.http.HttpResponse<Void>) org.mockito.Mockito.mock(java.net.http.HttpResponse.class);
            org.mockito.Mockito.when(uploaded.statusCode()).thenReturn(status);
            org.mockito.Mockito.when(http.send(org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.<java.net.http.HttpResponse.BodyHandler<Void>>any())).thenReturn(uploaded);
            var file = new com.fasc.open.api.v5_1.res.doc.FileId(); file.setFileId("file-new");
            var processed = new com.fasc.open.api.v5_1.res.doc.FileProcessRes(); processed.setFileIdList(java.util.List.of(file));
            org.mockito.Mockito.when(docs.process(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> successful(processed));
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
