package com.tradepass.module.contract.service.archive;

import com.tradepass.framework.common.util.FileTypeInspector;
import com.tradepass.module.file.api.file.ObjectStorageService;

import com.tradepass.framework.storage.config.StorageProperties;
import com.tradepass.module.contract.api.contract.dto.ContractRespDTO;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContractArchiveServiceTest {

    @Test
    void freezesActivePdfAndAlwaysDownloadsTheArchivedVersion() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ContractPdfService pdfService = mock(ContractPdfService.class);
        ObjectStorageService storage = mock(ObjectStorageService.class);
        StorageProperties properties = new StorageProperties();
        properties.setKeyPrefix("tradepass");
        ContractRespDTO contract = contract("ACTIVE");
        byte[] pdf = "%PDF-frozen".getBytes();
        String sha256 = FileTypeInspector.sha256(pdf);
        String objectKey = "tradepass/contract/2026/07/甲方_乙方/HT-8/v1/contract.pdf";
        ContractArchiveService.ArchiveRecord record = new ContractArchiveService.ArchiveRecord(
                5L, 8L, 1, "bucket", objectKey,
                "version-1", "合同.pdf", "application/pdf", (long) pdf.length, sha256);
        when(storage.isEnabled()).thenReturn(true);
        when(pdfService.generate(contract)).thenReturn(pdf);
        when(pdfService.fileName(contract)).thenReturn("合同.pdf");
        when(storage.putImmutable(anyString(), any(byte[].class), anyString(), anyString()))
                .thenReturn(new ObjectStorageService.StoredObject(
                        "CLOUDBASE_COS", "bucket", record.objectKey(), "version-1", "etag",
                        "CLOUDBASE_MANAGED", pdf.length, sha256));
        doReturn(List.of(), List.of(record), List.of(record))
                .when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
        when(storage.get(any(ObjectStorageService.ObjectReference.class))).thenReturn(pdf);
        ContractArchiveService service = new ContractArchiveServiceImpl(
                jdbc, pdfService, storage, properties);

        service.archiveOnApproval(contract, 7L);
        ContractArchiveService.PdfRespVO downloaded = service.getPdf(contract, 7L);

        assertThat(downloaded.data()).containsExactly(pdf);
        assertThat(downloaded.sha256()).isEqualTo(sha256);
        verify(storage).putImmutable(eq(objectKey),
                any(byte[].class), eq("application/pdf"), eq(sha256));
        verify(storage).get(any(ObjectStorageService.ObjectReference.class));
    }

    @Test
    void storesSignedPdfByContractMonthNumberAndPartyRoles() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ContractPdfService pdfService = mock(ContractPdfService.class);
        ObjectStorageService storage = mock(ObjectStorageService.class);
        StorageProperties properties = new StorageProperties();
        properties.setKeyPrefix("sqt-1461413991/prod");
        ContractRespDTO contract = new ContractRespDTO("2104760958844149761", "HT-2026/09-29 47E4515D",
                "2104741333007863810", "9", "上海与与企业", "PURCHASE",
                "购销合同", "模板", BigDecimal.TEN, "2026-09-01", "2026-12-31", "{}",
                "ACTIVE", 1, "6", "7", "2026-09-29T10:00:00", "2026-09-29T09:00:00",
                "河北满满贸易有限公司", "上海 与与/企业",
                "9", "2104741333007863810", "河北满满贸易有限公司", "SALE", "INCOMING");
        byte[] pdf = "%PDF-signed".getBytes();
        String sha256 = FileTypeInspector.sha256(pdf);
        when(storage.isEnabled()).thenReturn(true);
        when(pdfService.fileName(contract)).thenReturn("购销合同.pdf");
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        when(storage.putImmutable(anyString(), any(byte[].class), anyString(), anyString()))
                .thenReturn(new ObjectStorageService.StoredObject(
                        "CLOUDBASE_COS", "bucket", "stored", "version-1", "etag",
                        "CLOUDBASE_MANAGED", pdf.length, sha256));
        ContractArchiveService service = new ContractArchiveServiceImpl(
                jdbc, pdfService, storage, properties);

        service.archiveSignedPdf(contract, pdf, "sign-task", 7L);

        verify(storage).putImmutable(eq(
                "sqt-1461413991/prod/contract/2026/09/河北满满贸易有限公司_上海与与_企业/HT-2026_09-2947E4515D/v1/signed.pdf"),
                any(byte[].class), eq("application/pdf"), eq(sha256));
    }

    @Test
    void keepsDraftPdfDynamicWhenCloudStorageIsDisabled() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ContractPdfService pdfService = mock(ContractPdfService.class);
        ObjectStorageService storage = mock(ObjectStorageService.class);
        ContractRespDTO contract = contract("PENDING");
        byte[] pdf = "%PDF-draft".getBytes();
        when(pdfService.generate(contract)).thenReturn(pdf);
        when(pdfService.fileName(contract)).thenReturn("草稿.pdf");
        ContractArchiveService service = new ContractArchiveServiceImpl(
                jdbc, pdfService, storage, new StorageProperties());

        assertThat(service.getPdf(contract, 7L).data()).containsExactly(pdf);
    }

    private ContractRespDTO contract(String status) {
        return new ContractRespDTO("8", "HT-8", "3", "4", "乙方", "SALE",
                "合同", "模板", BigDecimal.TEN, "2026-07-01", "2026-12-31", "{}",
                status, 1, "6", "7", "2026-07-31T09:00:00", "2026-07-01T09:00:00",
                "甲方", "乙方",
                "3", "4", "乙方", "SALE", "OUTGOING");
    }
}
