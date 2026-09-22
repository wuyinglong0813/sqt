package com.tradepass.framework.fadada.core;

import com.fasc.open.api.bean.base.BaseRes;
import com.fasc.open.api.bean.common.Actor;
import com.fasc.open.api.bean.common.Field;
import com.fasc.open.api.bean.common.FieldCorpSeal;
import com.fasc.open.api.bean.common.FieldPosition;
import com.fasc.open.api.bean.common.OpenId;
import com.fasc.open.api.exception.ApiException;
import com.fasc.open.api.v5_1.client.DocClient;
import com.fasc.open.api.v5_1.client.OpenApiClient;
import com.fasc.open.api.v5_1.client.SignTaskClient;
import com.fasc.open.api.v5_1.req.doc.FddFileUrl;
import com.fasc.open.api.v5_1.req.doc.FileProcessReq;
import com.fasc.open.api.v5_1.req.doc.GetUploadUrlReq;
import com.fasc.open.api.v5_1.req.signtask.AddActorsInfo;
import com.fasc.open.api.v5_1.req.signtask.AddDocInfo;
import com.fasc.open.api.v5_1.req.signtask.AddSignConfigInfo;
import com.fasc.open.api.v5_1.req.signtask.AddSignFieldInfo;
import com.fasc.open.api.v5_1.req.signtask.CancelSignTaskCreateReq;
import com.fasc.open.api.v5_1.req.signtask.CreateSignTaskReq;
import com.fasc.open.api.v5_1.req.signtask.GetOwnerDownloadUrlReq;
import com.fasc.open.api.v5_1.req.signtask.GetSignTaskImgDownloadUrlReq;
import com.fasc.open.api.v5_1.req.signtask.ListSignTaskActorReq;
import com.fasc.open.api.v5_1.req.signtask.SignTaskActorGetUrlReq;
import com.fasc.open.api.v5_1.req.signtask.SignTaskBaseReq;
import com.fasc.open.api.v5_1.req.signtask.SignTaskCancelReq;
import com.fasc.open.api.v5_1.res.doc.FileId;
import com.fasc.open.api.v5_1.res.doc.FileProcessRes;
import com.fasc.open.api.v5_1.res.doc.GetUploadUrlRes;
import com.fasc.open.api.v5_1.res.signtask.CancelSignTaskCreateRes;
import com.fasc.open.api.v5_1.res.signtask.CreateSignTaskRes;
import com.fasc.open.api.v5_1.res.signtask.ListSignTaskActorRes;
import com.fasc.open.api.v5_1.res.signtask.OwnerDownloadUrlRes;
import com.fasc.open.api.v5_1.res.signtask.GetSigntaskImgDownloadUrlRes;
import com.fasc.open.api.v5_1.res.signtask.SignTaskActorGetUrlRes;
import com.fasc.open.api.v5_1.res.signtask.SignTaskDetailRes;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.fadada.config.FadadaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
public class SdkFadadaSigningGateway implements FadadaSigningGateway {
    private static final Logger log = LoggerFactory.getLogger(SdkFadadaSigningGateway.class);
    private static final int MAX_SIGNED_FILE_SIZE = 60 * 1024 * 1024;
    private static final int MAX_PREVIEW_PAGE_SIZE = 8 * 1024 * 1024;
    private static final int MAX_PREVIEW_PAGE_COUNT = 100;
    private final DocClient docClient;
    private final SignTaskClient signTaskClient;
    private final FadadaAccessTokenProvider tokenProvider;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build();

    public SdkFadadaSigningGateway(FadadaProperties properties, FadadaAccessTokenProvider tokenProvider) {
        OpenApiClient client = new OpenApiClient(
                properties.getAppId(), properties.getAppSecret(), properties.getServerUrl());
        this.docClient = new DocClient(client);
        this.signTaskClient = new SignTaskClient(client);
        this.tokenProvider = tokenProvider;
    }

    @Override
    public CreatedTask createTask(CreateTaskCommand command) {
        String fileId = uploadAndProcess(command.pdf(), command.fileName());
        String docId = "contract-doc";
        CreateSignTaskReq request = new CreateSignTaskReq();
        request.setAccessToken(tokenProvider.get());
        request.setInitiator(OpenId.getInstance("corp", command.initiatorOpenCorpId()));
        request.setSignTaskSubject(command.subject());
        request.setSignDocType("contract");
        request.setAutoStart(true);
        request.setAutoFinish(true);
        request.setAutoFillFinalize(true);
        request.setSignInOrder(true);
        request.setFileFormat("pdf");
        request.setTransReferenceId(command.contractReference());
        request.setCallbackUrl(command.callbackUrl());

        AddDocInfo document = new AddDocInfo();
        document.setDocId(docId);
        document.setDocName(command.fileName());
        document.setDocFileId(fileId);
        document.setDocFields(List.of(sealField("supplier-seal", "供方盖章"),
                sealField("buyer-seal", "需方盖章")));
        request.setDocs(List.of(document));
        request.setActors(List.of(
                actor(command.initiatorActorId(), command.initiatorName(), command.initiatorOpenCorpId(),
                        command.initiatorSealId(), docId, command.initiatorActorId().equals(command.supplierActorId())
                                ? "supplier-seal" : "buyer-seal", 1),
                actor(command.counterpartyActorId(), command.counterpartyName(), command.counterpartyOpenCorpId(),
                        command.counterpartySealId(), docId, command.counterpartyActorId().equals(command.supplierActorId())
                                ? "supplier-seal" : "buyer-seal", 2)));
        CreateSignTaskRes response = invoke(() -> signTaskClient.create(request), "创建合同签署任务");
        if (!hasText(response.getSignTaskId())) throw new BusinessException("合同签署任务创建失败，请稍后重试");
        return new CreatedTask(response.getSignTaskId(), fileId, docId);
    }

    @Override
    public String actorUrl(String signTaskId, String actorId, String clientUserId, String redirectMiniAppUrl) {
        SignTaskActorGetUrlReq request = new SignTaskActorGetUrlReq();
        request.setAccessToken(tokenProvider.get());
        request.setSignTaskId(signTaskId);
        request.setActorId(actorId);
        request.setClientUserId(clientUserId);
        request.setRedirectMiniAppUrl(redirectMiniAppUrl);
        SignTaskActorGetUrlRes response = invoke(() -> signTaskClient.signTaskActorGetUrl(request), "获取合同签署地址");
        return requiredActorEmbedUrl(response);
    }

    @Override
    public TaskStatus status(String signTaskId) {
        SignTaskBaseReq detailRequest = new SignTaskBaseReq();
        detailRequest.setAccessToken(tokenProvider.get());
        detailRequest.setSignTaskId(signTaskId);
        SignTaskDetailRes detail = invoke(() -> signTaskClient.getDetail(detailRequest), "查询合同签署状态");
        ListSignTaskActorReq actorRequest = new ListSignTaskActorReq();
        actorRequest.setAccessToken(tokenProvider.get());
        actorRequest.setSignTaskId(signTaskId);
        List<ListSignTaskActorRes> actors = invoke(() -> signTaskClient.listSignTaskActor(actorRequest),
                "查询合同参与方状态");
        return new TaskStatus(signTaskId, detail.getSignTaskStatus(), actors == null ? List.of() : actors.stream()
                .map(value -> new ActorStatus(value.getActorId(), value.getSignStatus())).toList(),
                detail.getOriginalSignTaskId(), detail.getAbolishedSignTaskId());
    }

    @Override
    public byte[] downloadSignedPdf(String signTaskId, String ownerOpenCorpId, String fileName) {
        GetOwnerDownloadUrlReq request = new GetOwnerDownloadUrlReq();
        request.setAccessToken(tokenProvider.get());
        request.setOwnerId(OpenId.getInstance("corp", ownerOpenCorpId));
        request.setSignTaskId(signTaskId);
        request.setCompression(false);
        request.setDownloadMode("download");
        request.setCustomName(fileName);
        OwnerDownloadUrlRes response = invoke(() -> signTaskClient.getOwnerDownloadUrl(request), "获取签署文件");
        return download(response.getDownloadUrl());
    }

    @Override
    public byte[] downloadSignedPreviewPage(String signTaskId, String ownerOpenCorpId) {
        GetSignTaskImgDownloadUrlReq request = new GetSignTaskImgDownloadUrlReq();
        request.setAccessToken(tokenProvider.get());
        request.setOwnerId(OpenId.getInstance("corp", ownerOpenCorpId));
        request.setSignTaskId(signTaskId);
        GetSigntaskImgDownloadUrlRes response = invoke(
                () -> signTaskClient.getSignTaskOwnerSlicingTicketId(request),
                "获取签署合同预览");
        return lastPngFromZip(download(response.getSignTaskImgUrl()));
    }

    @Override
    public void cancel(String signTaskId, String reason) {
        SignTaskCancelReq request = new SignTaskCancelReq();
        request.setAccessToken(tokenProvider.get());
        request.setSignTaskId(signTaskId);
        request.setTerminationNote(reason);
        invokeNoData(() -> signTaskClient.cancel(request), "撤回合同签署任务");
    }

    @Override
    public String abolish(String signTaskId, String reason, String callbackUrl) {
        CancelSignTaskCreateReq request = new CancelSignTaskCreateReq();
        request.setAccessToken(tokenProvider.get());
        request.setSignTaskId(signTaskId);
        request.setReason(reason);
        request.setFollowOriginalConfig(true);
        request.setAutoStart(true);
        request.setCallbackUrl(callbackUrl);
        CancelSignTaskCreateRes response = invoke(() -> signTaskClient.abolishSignTask(request), "创建合同作废任务");
        if (!hasText(response.getAbolishedSignTaskId())) throw new BusinessException("合同作废任务创建失败，请稍后重试");
        return response.getAbolishedSignTaskId();
    }

    private String uploadAndProcess(byte[] pdf, String fileName) {
        GetUploadUrlReq uploadRequest = new GetUploadUrlReq();
        uploadRequest.setAccessToken(tokenProvider.get());
        uploadRequest.setFileType("doc");
        uploadRequest.setStorageType("cloud");
        GetUploadUrlRes upload = invoke(() -> docClient.getUploadFileUrl(uploadRequest), "获取合同上传地址");
        put(upload.getUploadUrl(), pdf);
        FddFileUrl source = new FddFileUrl();
        source.setFileType("doc");
        source.setFddFileUrl(upload.getFddFileUrl());
        source.setFileName(fileName);
        source.setFileFormat("pdf");
        FileProcessReq processRequest = new FileProcessReq();
        processRequest.setAccessToken(tokenProvider.get());
        processRequest.setStorageType("cloud");
        processRequest.setSeparation(false);
        processRequest.setFddFileUrlList(List.of(source));
        FileProcessRes processed = invoke(() -> docClient.process(processRequest), "处理合同文件");
        FileId file = processed.getFileIdList() == null || processed.getFileIdList().isEmpty()
                ? null : processed.getFileIdList().get(0);
        if (file == null || !hasText(file.getFileId())) throw new BusinessException("合同文件处理失败，请稍后重试");
        return file.getFileId();
    }

    private AddActorsInfo actor(String actorId, String name, String openCorpId, String sealId,
                                String docId, String fieldId, int order) {
        Actor actor = new Actor();
        actor.setActorId(actorId);
        actor.setActorType("corp");
        actor.setActorName(name);
        actor.setPermissions(List.of("sign"));
        actor.setActorOpenId(openCorpId);
        actor.setSendNotification(false);
        actor.setSendInSiteMessage(false);
        AddSignFieldInfo signField = new AddSignFieldInfo();
        signField.setFieldDocId(docId);
        signField.setFieldId(fieldId);
        signField.setFieldName(fieldId);
        signField.setSealId(parseSealId(sealId));
        signField.setMoveable(false);
        AddSignConfigInfo config = new AddSignConfigInfo();
        config.setOrderNo(order);
        config.setRequestVerifyFree(false);
        config.setReadingToEnd(true);
        config.setResizeSeal(false);
        AddActorsInfo result = new AddActorsInfo();
        result.setActor(actor);
        result.setSignFields(List.of(signField));
        result.setSignConfigInfo(config);
        return result;
    }

    private Field sealField(String fieldId, String keyword) {
        FieldPosition position = new FieldPosition();
        position.setPositionMode("keyword");
        position.setPositionKeyword(keyword);
        position.setKeywordOffsetX("0");
        position.setKeywordOffsetY("0");
        FieldCorpSeal seal = new FieldCorpSeal();
        seal.setWidth(42);
        seal.setHeight(42);
        seal.setFollowSignSize(true);
        Field field = new Field();
        field.setFieldId(fieldId);
        field.setFieldName(keyword);
        field.setPosition(position);
        field.setFieldType("corp_seal");
        field.setFieldCorpSeal(seal);
        field.setMoveable(false);
        return field;
    }

    private void put(String url, byte[] pdf) {
        URI uri = secureUri(url);
        try {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/octet-stream")
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(pdf)).build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException("合同文件上传失败，请稍后重试");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException("合同文件上传中断，请重试");
        } catch (java.io.IOException exception) {
            throw new BusinessException("合同文件上传失败，请稍后重试");
        }
    }

    private byte[] download(String url) {
        URI uri = secureUri(url);
        try {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300
                    || response.body() == null || response.body().length == 0
                    || response.body().length > MAX_SIGNED_FILE_SIZE) {
                throw new BusinessException("签署文件下载失败，请稍后重试");
            }
            return response.body();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException("签署文件下载中断，请重试");
        } catch (java.io.IOException exception) {
            throw new BusinessException("签署文件下载失败，请稍后重试");
        }
    }

    private URI secureUri(String value) {
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !hasText(uri.getHost())) throw new IllegalArgumentException();
            return uri;
        } catch (RuntimeException exception) {
            throw new BusinessException("电子签文件地址无效");
        }
    }

    static byte[] lastPngFromZip(byte[] zipData) {
        if (zipData == null || zipData.length == 0) {
            throw new BusinessException("签署合同预览内容为空");
        }
        byte[] selected = null;
        int selectedPage = Integer.MIN_VALUE;
        int fallbackOrder = 0;
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zipData))) {
            ZipEntry entry;
            int pageCount = 0;
            while ((entry = input.getNextEntry()) != null) {
                if (entry.isDirectory() || !entry.getName().toLowerCase(Locale.ROOT).endsWith(".png")) {
                    input.closeEntry();
                    continue;
                }
                if (++pageCount > MAX_PREVIEW_PAGE_COUNT) {
                    throw new BusinessException("签署合同预览页数过多");
                }
                ByteArrayOutputStream page = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int total = 0;
                int length;
                while ((length = input.read(buffer)) != -1) {
                    total += length;
                    if (total > MAX_PREVIEW_PAGE_SIZE) {
                        throw new BusinessException("签署合同预览页过大");
                    }
                    page.write(buffer, 0, length);
                }
                byte[] data = page.toByteArray();
                if (!isPng(data)) {
                    throw new BusinessException("签署合同预览格式不正确");
                }
                int pageNumber = pageNumber(entry.getName(), fallbackOrder++);
                if (selected == null || pageNumber >= selectedPage) {
                    selected = data;
                    selectedPage = pageNumber;
                }
                input.closeEntry();
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (java.io.IOException exception) {
            throw new BusinessException("签署合同预览读取失败，请打开归档 PDF 查看");
        }
        if (selected == null) {
            throw new BusinessException("签署合同预览尚未生成，请打开归档 PDF 查看");
        }
        return selected;
    }

    private static int pageNumber(String name, int fallback) {
        String baseName = name == null ? "" : name.replace('\\', '/');
        int slash = baseName.lastIndexOf('/');
        if (slash >= 0) baseName = baseName.substring(slash + 1);
        int dot = baseName.lastIndexOf('.');
        if (dot > 0) baseName = baseName.substring(0, dot);
        for (int end = baseName.length(); end > 0; end--) {
            if (!Character.isDigit(baseName.charAt(end - 1))) continue;
            int start = end - 1;
            while (start > 0 && Character.isDigit(baseName.charAt(start - 1))) start--;
            try {
                return Integer.parseInt(baseName.substring(start, end));
            } catch (NumberFormatException ignored) {
                break;
            }
        }
        return fallback;
    }

    private static boolean isPng(byte[] data) {
        return data != null && data.length >= 8
                && (data[0] & 0xff) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G'
                && (data[4] & 0xff) == 0x0d && (data[5] & 0xff) == 0x0a
                && (data[6] & 0xff) == 0x1a && (data[7] & 0xff) == 0x0a;
    }

    private Long parseSealId(String value) {
        try { return Long.valueOf(value); }
        catch (RuntimeException exception) { throw new BusinessException("企业电子印章标识无效，请重新同步印章"); }
    }

    private <T> T invoke(ApiCall<T> call, String action) {
        try {
            BaseRes<T> response = call.call();
            if (response == null || !response.isSuccess() || response.getData() == null) {
                log.warn("Electronic signature {} failed: code={}, requestId={}", action,
                        response == null ? "null" : response.getCode(), response == null ? "" : response.getRequestId());
                throw new BusinessException(action + "失败，请稍后重试");
            }
            return response.getData();
        } catch (ApiException exception) {
            log.warn("Electronic signature {} failed", action, exception);
            throw new BusinessException(action + "失败，请稍后重试");
        }
    }

    private void invokeNoData(ApiCall<Void> call, String action) {
        try {
            BaseRes<Void> response = call.call();
            if (response == null || !response.isSuccess()) throw new BusinessException(action + "失败，请稍后重试");
        } catch (ApiException exception) {
            log.warn("Electronic signature {} failed", action, exception);
            throw new BusinessException(action + "失败，请稍后重试");
        }
    }

    static String requiredActorEmbedUrl(SignTaskActorGetUrlRes response) {
        String embedUrl = response == null ? null : response.getActorSignTaskEmbedUrl();
        if (!hasText(embedUrl)) throw new BusinessException("未获取到小程序合同签署地址，请稍后重试");
        return embedUrl;
    }

    private static boolean hasText(String value) { return value != null && !value.isBlank(); }
    @FunctionalInterface private interface ApiCall<T> { BaseRes<T> call() throws ApiException; }
}
