package com.tradepass.module.trade.service.logistics;

import com.tradepass.framework.common.util.FileTypeInspector;
import com.tradepass.module.file.api.file.ObjectStorageService;
import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.framework.audit.core.AuditLogService;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.module.trade.dal.dataobject.logistics.LogisticsDocumentDO;
import com.tradepass.framework.storage.config.StorageProperties;
import com.tradepass.module.contract.api.contract.dto.TradeContractRespDTO;
import com.tradepass.module.trade.dal.mysql.logistics.LogisticsDocumentMapper;
import com.tradepass.module.contract.api.contract.ContractReader;
import com.tradepass.module.contract.api.contract.ContractReader.*;
import com.tradepass.support.MybatisTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LogisticsDocumentServiceTest {
    private LogisticsDocumentMapper documentMapper;
    private ContractReader contractMapper;
    private AccessControlOperations accessControlService;
    private AuditLogService auditLogService;
    private LogisticsDocumentService service;
    private TradeContractRespDTO contract;

    @BeforeEach
    void setUp() {
        MybatisTestSupport.initialize(LogisticsDocumentDO.class, TradeContractRespDTO.class);
        documentMapper = mock(LogisticsDocumentMapper.class);
        contractMapper = mock(ContractReader.class);
        accessControlService = mock(AccessControlOperations.class);
        auditLogService = mock(AuditLogService.class);
        service = new LogisticsDocumentServiceImpl(
                documentMapper, contractMapper, accessControlService, auditLogService);

        contract = new TradeContractRespDTO();
        contract.setId(44L);
        contract.setCompanyId(3L);
        contract.setCounterpartyCompanyId(4L);
        when(contractMapper.selectById(44L)).thenReturn(contract);
        AuthContext.set(7L, 3L);
    }

    @AfterEach
    void clearContext() {
        AuthContext.clear();
    }

    @Test
    void uploadsAndListsRealImageMetadataWithoutReturningBlob() {
        AtomicReference<LogisticsDocumentDO> inserted = new AtomicReference<>();
        doAnswer(invocation -> {
            LogisticsDocumentDO document = invocation.getArgument(0);
            document.setId(9L);
            inserted.set(document);
            return 1;
        }).when(documentMapper).insert(any(LogisticsDocumentDO.class));
        when(documentMapper.selectById(9L)).thenAnswer(invocation -> inserted.get());
        byte[] jpeg = new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x01};

        Map<String, Object> created = service.upload(44L, "../物流单.jpg", jpeg);

        assertThat(created).containsEntry("id", 9L)
                .containsEntry("contentType", "image/jpeg")
                .containsEntry("fileSize", 4L)
                .containsEntry("originalName", ".._物流单.jpg");
        assertThat(inserted.get().getImageData()).containsExactly(jpeg);
        verify(accessControlService)
                .requireAnyPermission(3L, "contract_sign", "order_create");

        when(documentMapper.selectList(any(Wrapper.class))).thenReturn(List.of(inserted.get()));
        List<Map<String, Object>> listed = service.listDocuments(44L);
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0)).doesNotContainKey("imageData");
    }

    @Test
    void rejectsPayloadThatIsNotAnImage() {
        assertThatThrownBy(() -> service.upload(44L, "物流单.txt", "not-image".getBytes()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("仅支持 JPG、PNG、GIF 或 WebP 图片");
    }

    @Test
    void letsEitherContractPartyReadTheUploadedImage() {
        LogisticsDocumentDO document = new LogisticsDocumentDO();
        document.setId(9L);
        document.setCompanyId(3L);
        document.setContractId(44L);
        document.setOriginalName("物流单.png");
        document.setContentType("image/png");
        document.setImageData(new byte[]{1, 2, 3});
        when(documentMapper.selectById(9L)).thenReturn(document);
        AuthContext.set(8L, 4L);

        assertThat(service.getImage(9L)).isSameAs(document);
        verify(accessControlService)
                .requireAnyPermission(4L, "contract_view", "contract_sign");
    }

    @Test
    void storesNewImageInCloudStorageAndHydratesOnlyForAuthorizedDownload() {
        ObjectStorageService storage = mock(ObjectStorageService.class);
        when(storage.isEnabled()).thenReturn(true);
        byte[] jpeg = new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x01};
        String sha256 = FileTypeInspector.sha256(jpeg);
        when(storage.putImmutable(any(String.class), any(byte[].class), any(String.class), any(String.class)))
                .thenReturn(new ObjectStorageService.StoredObject(
                        "CLOUDBASE_COS", "bucket", "tradepass/file/3/44/logistics/file.jpg",
                        "v1", "etag", "CLOUDBASE_MANAGED", jpeg.length, sha256));
        LogisticsDocumentService ossService = new LogisticsDocumentServiceImpl(
                documentMapper, contractMapper, accessControlService, auditLogService,
                storage, new StorageProperties());
        AtomicReference<LogisticsDocumentDO> inserted = new AtomicReference<>();
        doAnswer(invocation -> {
            LogisticsDocumentDO document = invocation.getArgument(0);
            document.setId(10L);
            inserted.set(document);
            return 1;
        }).when(documentMapper).insert(any(LogisticsDocumentDO.class));
        when(documentMapper.selectById(10L)).thenAnswer(invocation -> inserted.get());

        assertThat(ossService.upload(44L, "物流单.jpg", jpeg)).containsEntry("id", 10L);
        assertThat(inserted.get().getImageData()).isNull();
        assertThat(inserted.get().getObjectVersionId()).isEqualTo("v1");
        verify(storage).putImmutable(argThat(key -> key.startsWith(
                        "tradepass/file/3/44/logistics/")), any(byte[].class),
                org.mockito.ArgumentMatchers.eq("image/jpeg"),
                org.mockito.ArgumentMatchers.eq(sha256));

        when(storage.get(any(ObjectStorageService.ObjectReference.class))).thenReturn(jpeg);
        assertThat(ossService.getImage(10L).getImageData()).containsExactly(jpeg);
    }
}
