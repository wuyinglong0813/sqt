package com.tradepass.module.settlement.service.reconciliation;

import com.tradepass.module.identity.api.permission.AccessControlOperations;

import com.tradepass.module.trade.api.document.dto.BusinessDocumentRespDTO;
import com.tradepass.module.identity.dal.dataobject.counterparty.CounterpartyRelationEntityDO;
import com.tradepass.module.contract.api.contract.dto.TradeContractRespDTO;
import com.tradepass.module.identity.api.counterparty.CounterpartyReaderImpl;
import com.tradepass.module.identity.dal.mysql.counterparty.CounterpartyRelationMapper;
import com.tradepass.module.settlement.dal.dataobject.reconciliation.ReconciliationEntryDO;
import com.tradepass.module.settlement.dal.mysql.reconciliation.ReconciliationEntryMapper;
import com.tradepass.support.MybatisTestSupport;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ReconciliationAccountServiceTest {
    private JdbcTemplate jdbc;
    private ReconciliationEntryMapper entryMapper;
    private ReconciliationAccountService service;

    @BeforeEach
    void setUp() {
        MybatisTestSupport.initialize(CounterpartyRelationEntityDO.class, ReconciliationEntryDO.class);
        jdbc = mock(JdbcTemplate.class);
        entryMapper = mock(ReconciliationEntryMapper.class);
        service = new ReconciliationAccountServiceImpl(jdbc, entryMapper,
                new CounterpartyReaderImpl(mock(CounterpartyRelationMapper.class)),
                mock(AccessControlOperations.class));
    }

    @Test
    void writesEveryApprovedSourceWithCanonicalCompanyPairAndIdempotentKey() {
        BusinessDocumentRespDTO salesOrder = new BusinessDocumentRespDTO();
        salesOrder.setId(31L);
        salesOrder.setCompanyId(9L);
        salesOrder.setRecipientCompanyId(3L);
        salesOrder.setContractId(12L);
        salesOrder.setDocumentNo("XS-31");
        service.recordSalesOrder(salesOrder, new BigDecimal("88.50"),
                LocalDate.of(2026, 8, 6), 7L, LocalDateTime.of(2026, 8, 6, 10, 0));

        TradeContractRespDTO purchaseContract = new TradeContractRespDTO();
        purchaseContract.setId(12L);
        purchaseContract.setCompanyId(3L);
        purchaseContract.setCounterpartyCompanyId(9L);
        purchaseContract.setDirection("PURCHASE");
        service.recordAttachment(purchaseContract, ReconciliationAccountService.INVOICE, 41L,
                LocalDate.of(2026, 8, 6), "FP-41", new BigDecimal("88.50"),
                9L, 8L, LocalDateTime.of(2026, 8, 6, 11, 0));

        ArgumentCaptor<ReconciliationEntryDO> args = ArgumentCaptor.forClass(ReconciliationEntryDO.class);
        verify(entryMapper, times(2)).upsertEntry(args.capture());
        assertThat(args.getAllValues().get(0).getCompanyAId()).isEqualTo(3L);
        assertThat(args.getAllValues().get(0).getCompanyBId()).isEqualTo(9L);
        assertThat(args.getAllValues().get(1).getSupplierCompanyId()).isEqualTo(9L);
        assertThat(args.getAllValues().get(1).getBuyerCompanyId()).isEqualTo(3L);
    }

    @Test
    void recordsReturnOrderAsNegativeSalesAmount() {
        BusinessDocumentRespDTO returnOrder = new BusinessDocumentRespDTO();
        returnOrder.setId(51L);
        returnOrder.setCompanyId(3L);
        returnOrder.setRecipientCompanyId(9L);
        returnOrder.setContractId(12L);
        returnOrder.setDocumentNo("TH-51");

        service.recordReturnOrder(returnOrder, new BigDecimal("1000.00"),
                LocalDate.of(2026, 8, 19), 7L, LocalDateTime.of(2026, 8, 19, 10, 0));

        ArgumentCaptor<ReconciliationEntryDO> args = ArgumentCaptor.forClass(ReconciliationEntryDO.class);
        verify(entryMapper).upsertEntry(args.capture());
        assertThat(args.getValue().getSourceType()).isEqualTo(ReconciliationAccountService.RETURN_ORDER);
        assertThat(args.getValue().getAmount()).isEqualTo(new BigDecimal("-1000.00"));
        assertThat(args.getValue().getSupplierCompanyId()).isEqualTo(9L);
        assertThat(args.getValue().getBuyerCompanyId()).isEqualTo(3L);
    }

    @Test
    void fillsTheProvidedWorkbookTemplateWithApprovedDocuments() throws Exception {
        List<ReconciliationAccountService.ContractAccount> contracts = List.of(
                new ReconciliationAccountService.ContractAccount(12L, "HT-001",
                        new BigDecimal("1000.00"), LocalDate.of(2026, 8, 6)));
        Map<Long, List<ReconciliationAccountService.WorkbookEntry>> entries = Map.of(12L, List.of(
                new ReconciliationAccountService.WorkbookEntry(ReconciliationAccountService.SALES_ORDER,
                        LocalDate.of(2026, 8, 7), new BigDecimal("500.00")),
                new ReconciliationAccountService.WorkbookEntry(ReconciliationAccountService.SALES_ORDER,
                        LocalDate.of(2026, 8, 8), new BigDecimal("200.00")),
                new ReconciliationAccountService.WorkbookEntry(ReconciliationAccountService.PAYMENT_VOUCHER,
                        LocalDate.of(2026, 8, 9), new BigDecimal("300.00")),
                new ReconciliationAccountService.WorkbookEntry(ReconciliationAccountService.INVOICE,
                        LocalDate.of(2026, 8, 10), new BigDecimal("100.00"))));

        byte[] data = service.generateWorkbook("甲公司与乙公司对账单", contracts, entries);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(data))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(3);
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(3).getCell(2).getStringCellValue()).isEqualTo("甲公司与乙公司对账单");
            assertThat(sheet.getMergedRegion(0).formatAsString()).isEqualTo("C4:J4");
            assertThat(sheet.getRow(5).getCell(3).getStringCellValue()).isEqualTo("HT-001");
            assertThat(sheet.getRow(5).getCell(4).getNumericCellValue()).isEqualTo(1000.00);
            assertThat(sheet.getRow(5).getCell(5).getNumericCellValue()).isEqualTo(500.00);
            assertThat(sheet.getRow(6).getCell(5).getNumericCellValue()).isEqualTo(200.00);
            assertThat(sheet.getRow(5).getCell(6).getNumericCellValue()).isEqualTo(300.00);
            assertThat(sheet.getRow(5).getCell(7).getCellFormula()).isEqualTo("SUM(F6:F7)-SUM(G6:G7)");
            assertThat(sheet.getRow(5).getCell(7).getNumericCellValue()).isEqualTo(400.00);
            assertThat(sheet.getRow(5).getCell(8).getNumericCellValue()).isEqualTo(100.00);
            assertThat(sheet.getRow(5).getCell(9).getNumericCellValue()).isEqualTo(600.00);
            assertThat(sheet.getRow(19).getCell(4).getCellFormula()).isEqualTo("SUM(E6:E19)");
            assertThat(sheet.getRow(19).getCell(4).getNumericCellValue()).isEqualTo(1000.00);
        }
    }

    @Test
    void extendsTheTemplateWhenApprovedDocumentsExceedTheOriginalRows() throws Exception {
        List<ReconciliationAccountService.WorkbookEntry> sales = java.util.stream.IntStream.range(0, 15)
                .mapToObj(index -> new ReconciliationAccountService.WorkbookEntry(
                        ReconciliationAccountService.SALES_ORDER,
                        LocalDate.of(2026, 8, 1).plusDays(index), new BigDecimal("10.00")))
                .toList();
        byte[] data = service.generateWorkbook("甲公司与乙公司对账单", List.of(
                new ReconciliationAccountService.ContractAccount(12L, "HT-001",
                        new BigDecimal("1000.00"), LocalDate.of(2026, 8, 1))), Map.of(12L, sales));

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(data))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(19).getCell(5).getNumericCellValue()).isEqualTo(10.00);
            assertThat(sheet.getRow(20).getCell(2).getStringCellValue()).isEqualTo("合计");
            assertThat(sheet.getRow(20).getCell(5).getCellFormula()).isEqualTo("SUM(F6:F20)");
            assertThat(sheet.getRow(20).getCell(5).getNumericCellValue()).isEqualTo(150.00);
            assertThat(sheet.getRow(20).getCell(9).getNumericCellValue()).isEqualTo(150.00);
            assertThat(sheet.getRow(19).getCell(5).getCellStyle().getIndex())
                    .isEqualTo(sheet.getRow(18).getCell(5).getCellStyle().getIndex());
        }
    }

    @Test
    void appliesTheTemplateDateFormatToEveryContractStartRow() throws Exception {
        List<ReconciliationAccountService.ContractAccount> contracts = List.of(
                new ReconciliationAccountService.ContractAccount(12L, "HT-001",
                        new BigDecimal("1000.00"), LocalDate.of(2026, 8, 1)),
                new ReconciliationAccountService.ContractAccount(13L, "HT-002",
                        new BigDecimal("2000.00"), LocalDate.of(2026, 8, 3)));
        Map<Long, List<ReconciliationAccountService.WorkbookEntry>> entries = Map.of(
                12L, List.of(
                        new ReconciliationAccountService.WorkbookEntry(ReconciliationAccountService.SALES_ORDER,
                                LocalDate.of(2026, 8, 1), new BigDecimal("10.00")),
                        new ReconciliationAccountService.WorkbookEntry(ReconciliationAccountService.SALES_ORDER,
                                LocalDate.of(2026, 8, 2), new BigDecimal("10.00"))),
                13L, List.of(new ReconciliationAccountService.WorkbookEntry(
                        ReconciliationAccountService.SALES_ORDER,
                        LocalDate.of(2026, 8, 3), new BigDecimal("20.00"))));

        byte[] data = service.generateWorkbook("甲公司与乙公司对账单", contracts, entries);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(data))) {
            var sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter(java.util.Locale.US);
            assertThat(formatter.formatCellValue(sheet.getRow(5).getCell(2))).isEqualTo("8/1/26");
            assertThat(formatter.formatCellValue(sheet.getRow(7).getCell(2))).isEqualTo("8/3/26");
            assertThat(sheet.getRow(7).getCell(2).getCellStyle().getIndex())
                    .isEqualTo(sheet.getRow(5).getCell(2).getCellStyle().getIndex());
        }
    }

    @Test
    void generatesAViewablePdfFromTheSameReconciliationAccountDetails() {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("businessDate", "2026-08-16");
        entry.put("contractNo", "HT-001");
        entry.put("sourceTypeText", "销售单");
        entry.put("documentNo", "XS-001");
        entry.put("directionText", "我方销售");
        entry.put("amount", new BigDecimal("88.50"));
        entry.put("approvedAt", "2026-08-16T10:30:00");
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("mySalesAmount", new BigDecimal("88.50"));
        account.put("myPurchaseAmount", BigDecimal.ZERO);
        account.put("receivedPaymentAmount", BigDecimal.ZERO);
        account.put("paidPaymentAmount", BigDecimal.ZERO);
        account.put("receivableBalance", new BigDecimal("88.50"));
        account.put("payableBalance", BigDecimal.ZERO);
        account.put("entries", List.of(entry));

        byte[] pdf = new ReconciliationPdfServiceImpl(null, null)
                .generatePdf("甲公司与乙公司对账单", account);

        assertThat(pdf.length).isGreaterThan(1000);
        assertThat(new String(pdf, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
    }
}
