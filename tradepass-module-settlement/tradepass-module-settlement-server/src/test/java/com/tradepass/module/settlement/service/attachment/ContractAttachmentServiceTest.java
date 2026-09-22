package com.tradepass.module.settlement.service.attachment;

import com.tradepass.framework.common.util.FileTypeInspector;
import com.tradepass.module.file.api.file.ObjectStorageService;
import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.framework.audit.core.AuditLogService;

import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.support.TestIds;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.module.contract.api.contract.dto.TradeContractRespDTO;
import com.tradepass.framework.storage.config.StorageProperties;
import com.tradepass.module.contract.api.contract.ContractReader;
import com.tradepass.module.contract.api.contract.ContractReader.*;
import com.tradepass.module.settlement.dal.dataobject.attachment.ContractAttachmentDO;
import com.tradepass.module.settlement.dal.mysql.attachment.ContractAttachmentMapper;
import com.tradepass.support.MybatisTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class ContractAttachmentServiceTest {
    private ContractAttachmentMapper attachmentMapper;
    private ContractReader contractMapper;
    private ContractAttachmentService service;

    @BeforeEach
    void setUp() {
        TestIds.use(8L);
        MybatisTestSupport.initialize(TradeContractRespDTO.class, ContractAttachmentDO.class);
        attachmentMapper = mock(ContractAttachmentMapper.class);
        contractMapper = mock(ContractReader.class);
        service = new ContractAttachmentServiceImpl(attachmentMapper, contractMapper,
                mock(AccessControlOperations.class), mock(AuditLogService.class));
        TradeContractRespDTO contract = new TradeContractRespDTO();
        contract.setId(12L);
        contract.setCompanyId(3L);
        contract.setCounterpartyCompanyId(4L);
        when(contractMapper.selectById(12L)).thenReturn(contract);
        AuthContext.set(7L, 3L);
    }

    @AfterEach
    void clearContext() {
        AuthContext.clear();
        TestIds.reset();
    }

    @Test
    void uploadsPaymentVoucherAndReturnsSharedMetadata() {
        TestIds.use(8L);
        stubListRow(8L, "PAYMENT_VOUCHER", "转款凭证.pdf", "application/pdf", 9L);

        Map<String, Object> uploaded = service.upload(12L, "payment_voucher", "../转款凭证.pdf",
                "%PDF-1.7".getBytes(), "2026-07-28", "100.126");
        assertThat(uploaded).containsEntry("id", 8L);
        assertThat(service.list(12L, "PAYMENT_VOUCHER").get(0)).containsEntry("id", 8L);
    }

    @Test
    void acceptsWordOnlyForOtherAttachments() throws Exception {
        byte[] docx = ooxml("word/document.xml");
        TestIds.use(9L);
        stubListRow(9L, "OTHER", "说明.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", (long) docx.length);

        assertThat(service.upload(12L, "OTHER", "说明.docx", docx, null, null))
                .containsEntry("id", 9L);
        assertThatThrownBy(() -> service.upload(12L, "PAYMENT_VOUCHER", "说明.docx",
                docx, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("转款凭证仅支持图片或 PDF");
    }

    @Test
    void acceptsOnlyImagesOrPdfForInvoicesAndGeneratesInvoiceNumber() throws Exception {
        byte[] pdf = "%PDF-1.7".getBytes();
        byte[] docx = ooxml("word/document.xml");
        TestIds.use(10L);
        stubListRow(10L, "INVOICE", "发票.pdf", "application/pdf", (long) pdf.length);

        assertThat(service.upload(12L, "INVOICE", "发票.pdf", pdf, null, null,
                null, "2026-07-28", "88.50"))
                .containsEntry("id", 10L);
        ArgumentCaptor<ContractAttachmentDO> values = ArgumentCaptor.forClass(ContractAttachmentDO.class);
        verify(attachmentMapper).insert(values.capture());
        assertThat(values.getValue().getInvoiceNo()).matches("FP-\\d{8}-[A-Z0-9]{8}");
        assertThatThrownBy(() -> service.upload(12L, "INVOICE", "发票.docx",
                docx, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("发票仅支持图片或 PDF");
    }

    @Test
    void letsEitherContractPartyReadAndRejectsInvalidInput() {
        byte[] data = "%PDF-1.7".getBytes();
        ContractAttachmentDO file = new ContractAttachmentDO();
        file.setId(8L);
        file.setContractId(12L);
        file.setOriginalName("凭证.pdf");
        file.setContentType("application/pdf");
        file.setFileData(data);
        file.setFileSize((long) data.length);
        when(attachmentMapper.selectFile(8L, 0)).thenReturn(file);

        AuthContext.set(8L, 4L);
        assertThat(service.getFile(8L).data()).containsExactly(data);

        AuthContext.set(7L, 3L);
        assertThatThrownBy(() -> service.list(12L, "unknown"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("附件分类不正确");
        assertThatThrownBy(() -> service.upload(12L, "PAYMENT_VOUCHER", "凭证.pdf",
                data, "bad-date", null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("转款日期格式不正确");
        assertThatThrownBy(() -> service.upload(12L, "PAYMENT_VOUCHER", "凭证.pdf",
                data, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("请输入转款金额");
        assertThatThrownBy(() -> service.upload(12L, "PAYMENT_VOUCHER", "凭证.pdf",
                data, null, "-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("转款金额格式不正确");

        AuthContext.set(7L, 99L);
        assertThatThrownBy(() -> service.list(12L, "OTHER"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("合同不存在");
    }

    @Test
    void reportsMissingAttachment() {
        when(attachmentMapper.selectFile(88L, 0)).thenReturn(null);
        assertThatThrownBy(() -> service.getFile(88L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("附件不存在");
    }

    @Test
    void requiresHandwrittenSignatureOnlyWhenApprovingPaymentVoucher() {
        AuthContext.set(8L, 4L);
        ContractAttachmentDO row = new ContractAttachmentDO();
        row.setId(8L);
        row.setContractId(12L);
        row.setUploaderCompanyId(3L);
        row.setRecipientCompanyId(4L);
        row.setCategory("PAYMENT_VOUCHER");
        row.setStatus("PENDING_CONFIRMATION");
        row.setOriginalName("转款凭证.pdf");
        row.setVoucherDate(LocalDate.of(2026, 8, 30));
        row.setVoucherAmount(new BigDecimal("100.00"));
        row.setCreatedBy(7L);
        when(attachmentMapper.selectRecord(8L)).thenReturn(row);

        assertThatThrownBy(() -> service.decide(8L, "APPROVE", ""))
                .isInstanceOf(BusinessException.class)
                .hasMessage("确认转款凭证前请先完成手写签名");
    }

    @Test
    void storesNewAttachmentInCloudStorageAndReadsItBackWithRecordedVersion() {
        ObjectStorageService storage = mock(ObjectStorageService.class);
        when(storage.isEnabled()).thenReturn(true);
        byte[] data = "%PDF-1.7".getBytes();
        String sha256 = FileTypeInspector.sha256(data);
        when(storage.putImmutable(anyString(), any(byte[].class), anyString(), anyString()))
                .thenReturn(new ObjectStorageService.StoredObject(
                        "CLOUDBASE_COS", "bucket", "tradepass/file/3/12/attachment/file.pdf",
                        "v1", "etag", "CLOUDBASE_MANAGED", data.length, sha256));
        StorageProperties properties = new StorageProperties();
        properties.setKeyPrefix("tradepass");
        ContractAttachmentService ossService = new ContractAttachmentServiceImpl(
                attachmentMapper, contractMapper, mock(AccessControlOperations.class), mock(AuditLogService.class),
                storage, properties);
        TestIds.use(8L);
        stubListRow(8L, "OTHER", "资料.pdf", "application/pdf", (long) data.length);

        assertThat(ossService.upload(12L, "OTHER", "资料.pdf", data, null, null))
                .containsEntry("id", 8L);
        verify(storage).putImmutable(argThat(key -> key.startsWith(
                        "tradepass/file/3/12/attachment/")), any(byte[].class),
                eq("application/pdf"), eq(sha256));

        ContractAttachmentDO reference = new ContractAttachmentDO();
        reference.setId(8L);
        reference.setContractId(12L);
        reference.setOriginalName("资料.pdf");
        reference.setContentType("application/pdf");
        reference.setStorageBucket("bucket");
        reference.setObjectKey("tradepass/file/3/12/attachment/file.pdf");
        reference.setObjectVersionId("v1");
        reference.setFileSize((long) data.length);
        reference.setSha256(sha256);
        when(attachmentMapper.selectFile(8L, 0)).thenReturn(reference);
        when(storage.get(any(ObjectStorageService.ObjectReference.class))).thenReturn(data);
        assertThat(ossService.getFile(8L).data()).containsExactly(data);
    }

    private void stubListRow(Long id, String category, String originalName, String contentType, Long fileSize) {
        ContractAttachmentDO row = new ContractAttachmentDO();
        row.setId(id);
        row.setContractId(12L);
        row.setUploaderCompanyId(3L);
        row.setRecipientCompanyId(4L);
        row.setCategory(category);
        row.setStatus("OTHER".equals(category) ? "APPROVED" : "PENDING_CONFIRMATION");
        row.setOriginalName(originalName);
        row.setContentType(contentType);
        row.setFileSize(fileSize);
        row.setCreatedBy(7L);
        row.setCreatedAt(LocalDateTime.of(2026, 7, 28, 10, 0));
        when(attachmentMapper.selectListWithUploader(anyLong(), anyString())).thenReturn(List.of(row));
    }

    private byte[] ooxml(String partName) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes());
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(partName));
            zip.write("<root/>".getBytes());
            zip.closeEntry();
        }
        return output.toByteArray();
    }
}
