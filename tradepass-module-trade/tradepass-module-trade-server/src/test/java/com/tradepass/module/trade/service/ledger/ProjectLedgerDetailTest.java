package com.tradepass.module.trade.service.ledger;

import com.tradepass.module.contract.api.contract.dto.TradeContractRespDTO;
import com.tradepass.module.settlement.api.reconciliation.ReconciliationAccountOperations.ProjectLedgerEntry;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectLedgerDetailTest {
    private static final LocalDate DATE = LocalDate.of(2026, 9, 1);
    private static final Map<String, Object> PROJECT = Map.of("id", 51L, "projectNo", "XM-51", "name", "建安中学改造项目");

    @Test
    void reproducesBothTemplateCompanyTotalsIncludingNegativeReturnsAndPaymentBasedUnbilledAmount() {
        Map<String, Object> detail = templateDetail();
        List<Map<String, Object>> groups = groups(detail);
        assertThat(detail).containsEntry("contractCount", 2).containsEntry("excludedContractCount", 0);
        assertThat(groups).hasSize(2);
        assertThat(totals(groups.get(0))).containsEntry("amount", money("8000"))
                .containsEntry("paymentAmount", money("8000")).containsEntry("unpaidAmount", money("0"))
                .containsEntry("invoiceAmount", money("7000")).containsEntry("unbilledAmount", money("1000"));
        assertThat(totals(groups.get(1))).containsEntry("amount", money("40000"))
                .containsEntry("paymentAmount", money("40000")).containsEntry("unpaidAmount", money("0"))
                .containsEntry("invoiceAmount", money("40000")).containsEntry("unbilledAmount", money("0"));
        assertThat(ProjectLedgerDetail.rows(groups.get(1))).anySatisfy(row -> {
            assertThat(row.get("name")).isEqualTo("退货单");
            assertThat(row.get("amount")).isEqualTo(money("-10000"));
        });
        assertThat(ProjectLedgerDetail.rows(groups.get(0)).get(0).get("amount")).isNull();
    }

    @Test
    void separatesPurchaseAndSaleForTheSameCompanyAndExcludesInactiveForeignAndUnassignedEntries() {
        var purchase = contract(12, 4, 9, "PURCHASE", "光屿行", "10000");
        var sale = contract(13, 9, 4, "PURCHASE", "当前企业", "20000");
        var inactive = contract(14, 4, 9, "SALE", "光屿行", "9000");
        inactive.setStatus("ABOLISHED");
        var foreign = contract(15, 7, 8, "SALE", "其他企业", "7000");
        var detail = ProjectLedgerDetail.build(PROJECT, 4, List.of(purchase, sale, inactive, foreign), Map.of(9L, "光屿行"),
                List.of(entry(1, 12, "SALES_ORDER", "1000"), entry(2, 13, "SALES_ORDER", "2000"),
                        entry(3, 14, "SALES_ORDER", "9000"), entry(4, 15, "SALES_ORDER", "7000"),
                        entry(5, 99, "PAYMENT_VOUCHER", "99999")));
        assertThat(detail).containsEntry("contractCount", 2).containsEntry("excludedContractCount", 2);
        assertThat(groups(detail)).extracting(group -> group.get("direction")).containsExactly("PURCHASE", "SALE");
        assertThat(groups(detail)).allSatisfy(group -> assertThat(group.get("counterpartyName")).isEqualTo("光屿行"));
        assertThat(totals(groups(detail).get(0)).get("amount")).isEqualTo(money("1000"));
        assertThat(totals(groups(detail).get(1)).get("amount")).isEqualTo(money("2000"));
    }

    @Test
    void appliesEveryReversalOnceAndCarriesChronologicalBalancesAcrossMultipleContracts() {
        var contract = contract(12, 4, 9, "PURCHASE", "光屿行", "10000");
        var laterContract = contract(13, 4, 9, "PURCHASE", "光屿行", "10000");
        laterContract.setStartDate(DATE.plusDays(3));
        var invoice = entry(4, 12, "INVOICE", "800");
        var detail = ProjectLedgerDetail.build(PROJECT, 4, List.of(laterContract, contract), Map.of(), List.of(
                entry(6, 12, "SALES_ORDER_VOID", "-1000"), invoice, entry(1, 12, "SALES_ORDER", "1000"),
                entry(2, 12, "RETURN_ORDER", "-100"), entry(3, 12, "PAYMENT_VOUCHER", "900"), invoice,
                entry(7, 12, "RETURN_ORDER_VOID", "100"), entry(8, 12, "PAYMENT_VOUCHER_VOID", "-900"),
                entry(9, 12, "INVOICE_VOID", "-800"), entry(10, 13, "SALES_ORDER", "200")));
        var group = groups(detail).get(0);
        assertThat(ProjectLedgerDetail.rows(group)).hasSize(11);
        assertThat(ProjectLedgerDetail.rows(group).get(0).get("name")).isEqualTo("合同");
        assertThat(totals(group)).containsEntry("amount", money("200")).containsEntry("paymentAmount", money("0"))
                .containsEntry("unpaidAmount", money("200")).containsEntry("invoiceAmount", money("0"))
                .containsEntry("unbilledAmount", money("0"));
        assertThat(ProjectLedgerDetail.rows(group)).anySatisfy(row -> assertThat(row.get("name")).isEqualTo("退货单（作废冲销）"));
    }

    @Test
    void exportsTemplateColumnsCompanySectionsAndLiveBalanceFormulas() throws Exception {
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(ProjectLedgerWorkbook.generate(templateDetail())))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(workbook.getNumberOfSheets()).isEqualTo(1);
            assertThat(sheet.getRow(2).getCell(1).getStringCellValue()).isEqualTo("建安中学改造项目台账明细");
            assertThat(sheet.getRow(5).getCell(1).getStringCellValue()).isEqualTo("光屿行贸易有限公司（采购）");
            for (int col = 1; col <= 8; col++) assertThat(sheet.getRow(6).getCell(col).getStringCellValue()).isEqualTo(ProjectLedgerDetail.COLUMNS.get(col - 1));
            var evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            assertThat(evaluator.evaluate(sheet.getRow(13).getCell(8)).getNumberValue()).isEqualTo(1000);
            assertThat(evaluator.evaluate(sheet.getRow(24).getCell(4)).getNumberValue()).isEqualTo(40000);
            assertThat(evaluator.evaluate(sheet.getRow(24).getCell(8)).getNumberValue()).isZero();
            // Changing a transfer must update both its running balance and the company subtotal.
            sheet.getRow(11).getCell(5).setCellValue(7500);
            evaluator.clearAllCachedResultValues();
            assertThat(evaluator.evaluate(sheet.getRow(11).getCell(6)).getNumberValue()).isEqualTo(500);
            assertThat(evaluator.evaluate(sheet.getRow(13).getCell(8)).getNumberValue()).isEqualTo(500);
        }
    }

    @Test
    void emptyProjectHasNoMadeUpSourceRowsAndStillExports() throws Exception {
        var detail = ProjectLedgerDetail.build(PROJECT, 4, List.of(), Map.of(), List.of());
        assertThat(groups(detail)).isEmpty();
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(ProjectLedgerWorkbook.generate(detail)))) {
            assertThat(workbook.getSheetAt(0).getRow(5).getCell(1).getStringCellValue()).contains("请先划分合同");
        }
    }

    private Map<String, Object> templateDetail() {
        return ProjectLedgerDetail.build(PROJECT, 4, List.of(
                contract(12, 4, 9, "PURCHASE", "光屿行贸易有限公司", "10000"),
                contract(13, 4, 10, "PURCHASE", "百盛电缆有限公司", "50000")), Map.of(), List.of(
                entry(1, 12, "SALES_ORDER", "1000"), entry(2, 12, "SALES_ORDER", "5000"), entry(3, 12, "SALES_ORDER", "2000"),
                entry(4, 12, "PAYMENT_VOUCHER", "8000"), entry(5, 12, "INVOICE", "7000"),
                entry(6, 13, "SALES_ORDER", "20000"), entry(7, 13, "SALES_ORDER", "10000"), entry(8, 13, "SALES_ORDER", "20000"),
                entry(9, 13, "PAYMENT_VOUCHER", "40000"), entry(10, 13, "RETURN_ORDER", "-10000"), entry(11, 13, "INVOICE", "40000")));
    }

    private static TradeContractRespDTO contract(long id, long companyId, long otherId, String direction, String name, String amount) {
        var contract = new TradeContractRespDTO();
        contract.setId(id); contract.setCompanyId(companyId); contract.setCounterpartyCompanyId(otherId);
        contract.setCounterpartyName(name); contract.setDirection(direction); contract.setStatus("ACTIVE");
        contract.setContractNo("HT-" + id); contract.setStartDate(DATE); contract.setAmount(money(amount));
        return contract;
    }

    private static ProjectLedgerEntry entry(long id, long contractId, String type, String amount) {
        LocalDate date = DATE.plusDays(id);
        return new ProjectLedgerEntry(id, contractId, type, date, type + "-" + id, money(amount), date.atStartOfDay());
    }

    private static BigDecimal money(String value) { return new BigDecimal(value).setScale(2); }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> groups(Map<String, Object> detail) { return (List<Map<String, Object>>) detail.get("groups"); }
    @SuppressWarnings("unchecked") private static Map<String, Object> totals(Map<String, Object> group) { return (Map<String, Object>) group.get("totals"); }
}
