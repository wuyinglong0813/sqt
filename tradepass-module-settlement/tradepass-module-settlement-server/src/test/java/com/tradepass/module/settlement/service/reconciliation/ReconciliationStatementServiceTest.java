package com.tradepass.module.settlement.service.reconciliation;

import com.tradepass.framework.common.util.FileTypeInspector;
import com.tradepass.module.file.api.file.ObjectStorageService;
import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.framework.audit.core.AuditLogService;

import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.support.TestIds;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.module.identity.dal.dataobject.counterparty.CounterpartyRelationEntityDO;
import com.tradepass.framework.storage.config.StorageProperties;
import com.tradepass.module.identity.api.counterparty.CounterpartyReaderImpl;
import com.tradepass.module.identity.dal.mysql.counterparty.CounterpartyRelationMapper;
import com.tradepass.module.settlement.dal.dataobject.reconciliation.ReconciliationStatementDO;
import com.tradepass.module.settlement.dal.mysql.reconciliation.ReconciliationStatementMapper;
import com.tradepass.support.MybatisTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
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

class ReconciliationStatementServiceTest {
    private ReconciliationStatementMapper statementMapper;
    private CounterpartyRelationMapper relationMapper;
    private ReconciliationStatementService service;

    @BeforeEach
    void setUp() {
        TestIds.use(18L);
        MybatisTestSupport.initialize(CounterpartyRelationEntityDO.class, ReconciliationStatementDO.class);
        statementMapper = mock(ReconciliationStatementMapper.class);
        relationMapper = mock(CounterpartyRelationMapper.class);
        service = new ReconciliationStatementServiceImpl(statementMapper, new CounterpartyReaderImpl(relationMapper),
                mock(AccessControlOperations.class), mock(AuditLogService.class));
        when(relationMapper.countActiveBetween(3L, 4L)).thenReturn(1L);
        AuthContext.set(7L, 3L);
    }

    @AfterEach
    void clearContext() {
        AuthContext.clear();
        TestIds.reset();
    }

    @Test
    void uploadsAndListsXlsxForActiveCounterparty() throws Exception {
        TestIds.use(18L);
        stubStatement(18L, "2026-07", "对账单.xlsx");

        Map<String, Object> uploaded = service.upload(4L, "2026-07", " 七月账单 ",
                "../对账单.xlsx", ooxml("xl/workbook.xml"));
        assertThat(uploaded).containsEntry("id", 18L);
        assertThat(service.list(4L).get(0)).containsEntry("id", 18L);
        assertThat(service.list(null).get(0)).containsEntry("id", 18L);
    }

    @Test
    void letsEitherPartyDownloadStatement() {
        byte[] data = new byte[]{1, 2, 3};
        ReconciliationStatementDO stored = new ReconciliationStatementDO();
        stored.setId(18L);
        stored.setIssuerCompanyId(3L);
        stored.setCounterpartyCompanyId(4L);
        stored.setOriginalName("对账单.xlsx");
        stored.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        stored.setFileData(data);
        stored.setFileSize((long) data.length);
        when(statementMapper.selectFile(18L)).thenReturn(stored);

        AuthContext.set(8L, 4L);
        assertThat(service.getFile(18L).data()).containsExactly(data);
        AuthContext.set(9L, 5L);
        assertThatThrownBy(() -> service.getFile(18L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("对账单不存在");
    }

    @Test
    void rejectsBadRelationPeriodTypeAndMissingFile() throws Exception {
        byte[] xlsx = ooxml("xl/workbook.xml");
        assertThatThrownBy(() -> service.upload(3L, "2026-07", "", "self.xlsx", xlsx))
                .isInstanceOf(BusinessException.class)
                .hasMessage("请选择合作企业");
        assertThatThrownBy(() -> service.upload(4L, "2026-13", "", "bad.xlsx", xlsx))
                .isInstanceOf(BusinessException.class)
                .hasMessage("对账期间格式应为 YYYY-MM");
        assertThatThrownBy(() -> service.upload(4L, "2026-07", "", "bad.pdf", "%PDF-1.7".getBytes()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("客户对账单仅支持 XLSX 文件");
        assertThatThrownBy(() -> service.upload(4L, "2026-07", "x".repeat(501), "bad.xlsx", xlsx))
                .isInstanceOf(BusinessException.class)
                .hasMessage("备注不能超过 500 字");

        when(relationMapper.countActiveBetween(3L, 4L)).thenReturn(0L);
        assertThatThrownBy(() -> service.list(4L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("合作企业关系不存在");

        when(statementMapper.selectFile(99L)).thenReturn(null);
        assertThatThrownBy(() -> service.getFile(99L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("对账单不存在");
    }

    @Test
    void storesAndReadsStatementThroughCloudStorage() throws Exception {
        ObjectStorageService storage = mock(ObjectStorageService.class);
        when(storage.isEnabled()).thenReturn(true);
        byte[] data = ooxml("xl/workbook.xml");
        String sha256 = FileTypeInspector.sha256(data);
        when(storage.putImmutable(anyString(), any(byte[].class), anyString(), anyString()))
                .thenReturn(new ObjectStorageService.StoredObject(
                        "CLOUDBASE_COS", "bucket", "tradepass/file/3/reconciliation/4/2026-07/file.xlsx",
                        "v1", "etag", "CLOUDBASE_MANAGED", data.length, sha256));
        ReconciliationStatementService ossService = new ReconciliationStatementServiceImpl(
                statementMapper, new CounterpartyReaderImpl(relationMapper), mock(AccessControlOperations.class), mock(AuditLogService.class),
                storage, new StorageProperties());
        TestIds.use(18L);
        stubStatement(18L, "2026-07", "账单.xlsx");

        assertThat(ossService.upload(4L, "2026-07", "", "账单.xlsx", data))
                .containsEntry("id", 18L);
        verify(storage).putImmutable(argThat(key -> key.startsWith(
                        "tradepass/file/3/reconciliation/4/2026-07/")), any(byte[].class),
                eq("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"), eq(sha256));

        ReconciliationStatementDO reference = new ReconciliationStatementDO();
        reference.setId(18L);
        reference.setIssuerCompanyId(3L);
        reference.setCounterpartyCompanyId(4L);
        reference.setOriginalName("账单.xlsx");
        reference.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        reference.setStorageBucket("bucket");
        reference.setObjectKey("tradepass/file/3/reconciliation/4/2026-07/file.xlsx");
        reference.setObjectVersionId("v1");
        reference.setFileSize((long) data.length);
        reference.setSha256(sha256);
        when(statementMapper.selectFile(18L)).thenReturn(reference);
        when(storage.get(any(ObjectStorageService.ObjectReference.class))).thenReturn(data);
        assertThat(ossService.getFile(18L).data()).containsExactly(data);
    }

    private void stubStatement(Long id, String period, String originalName) {
        ReconciliationStatementDO row = new ReconciliationStatementDO();
        row.setId(id);
        row.setIssuerCompanyId(3L);
        row.setCounterpartyCompanyId(4L);
        row.setStatementPeriod(period);
        row.setOriginalName(originalName);
        row.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        row.setFileSize(1L);
        row.setRemark("");
        row.setCreatedAt(LocalDateTime.of(2026, 7, 1, 10, 0));
        when(statementMapper.selectByCompanyPair(anyLong(), anyLong())).thenReturn(List.of(row));
        when(statementMapper.selectByCompany(anyLong())).thenReturn(List.of(row));
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
