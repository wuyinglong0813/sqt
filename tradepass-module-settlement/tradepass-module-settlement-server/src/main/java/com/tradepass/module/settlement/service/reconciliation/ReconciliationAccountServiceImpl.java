package com.tradepass.module.settlement.service.reconciliation;

import com.tradepass.module.contract.api.directory.ContractDirectoryOperations;
import com.tradepass.module.contract.api.directory.ContractDirectoryOperations.*;
import com.tradepass.module.identity.api.directory.IdentityDirectoryOperations;
import com.tradepass.module.identity.api.directory.IdentityDirectoryOperations.*;
import com.tradepass.module.settlement.api.reconciliation.ReconciliationAccountOperations;
import com.tradepass.module.settlement.api.reconciliation.ReconciliationAccountOperations.*;

import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.module.identity.api.permission.AccessControlOperations.*;

import com.tradepass.framework.mybatis.core.ApplicationIds;

import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.module.trade.api.document.dto.BusinessDocumentRespDTO;
import com.tradepass.module.contract.api.contract.dto.TradeContractRespDTO;
import com.tradepass.module.identity.api.counterparty.CounterpartyReader;
import com.tradepass.module.identity.api.counterparty.CounterpartyReader.*;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.ClassPathResource;
import com.tradepass.module.settlement.dal.dataobject.reconciliation.ReconciliationEntryDO;
import com.tradepass.module.settlement.dal.mysql.reconciliation.ReconciliationEntryMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ReconciliationAccountServiceImpl implements ReconciliationAccountService {
    public static final String SALES_ORDER = "SALES_ORDER";
    public static final String RETURN_ORDER = "RETURN_ORDER";
    public static final String PAYMENT_VOUCHER = "PAYMENT_VOUCHER";
    public static final String INVOICE = "INVOICE";
    private static final String WORKBOOK_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String WORKBOOK_TEMPLATE = "reconciliation/对账单.xlsx";
    private static final int DATA_START_ROW = 5;
    private static final int TEMPLATE_DATA_ROWS = 14;
    private static final int TEMPLATE_TOTAL_ROW = 19;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tradepass.module.identity.api.directory.IdentityDirectoryOperations identityDirectory;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tradepass.module.contract.api.directory.ContractDirectoryOperations contractDirectory;

    private final JdbcTemplate jdbc;
    private final ReconciliationEntryMapper entryMapper;
    private final CounterpartyReader relationMapper;
    private final AccessControlOperations accessControlService;

    public ReconciliationAccountServiceImpl(JdbcTemplate jdbc,
                                        ReconciliationEntryMapper entryMapper,
                                        CounterpartyReader relationMapper,
                                        AccessControlOperations accessControlService) {
        this.jdbc = jdbc;
        this.entryMapper = entryMapper;
        this.relationMapper = relationMapper;
        this.accessControlService = accessControlService;
    }

    public List<Map<String, Object>> listAccounts() { return listAccounts(null); }

    public List<Map<String, Object>> listAccounts(String role) {
        long companyId = AuthContext.requireCompanyId();
        accessControlService.requirePermission(companyId, "reconciliation");
        List<Map<String, Object>> counterparties = identityDirectory != null
                ? identityDirectory.counterparties(companyId).stream().map(c -> Map.<String, Object>of(
                        "counterpartyCompanyId", c.companyId(), "counterpartyName", c.name())).toList()
                : jdbc.query("""
                        SELECT DISTINCT pair.counterparty_id, company.name AS counterparty_name
                        FROM (
                            SELECT CASE WHEN relation.company_id = ?
                                        THEN relation.counterparty_company_id ELSE relation.company_id END AS counterparty_id
                            FROM counterparty_relation relation
                            WHERE relation.status = 'ACTIVE'
                              AND (relation.company_id = ? OR relation.counterparty_company_id = ?)
                              AND relation.counterparty_company_id IS NOT NULL
                        ) pair
                        JOIN company ON company.id = pair.counterparty_id
                        ORDER BY company.name, pair.counterparty_id
                        """, (rs, rowNum) -> Map.<String, Object>of(
                        "counterpartyCompanyId", rs.getLong("counterparty_id"),
                        "counterpartyName", rs.getString("counterparty_name")),
                companyId, companyId, companyId);
        List<Map<String, Object>> accounts = new ArrayList<>();
        for (Map<String, Object> counterparty : counterparties) {
            long counterpartyId = ((Number) counterparty.get("counterpartyCompanyId")).longValue();
            accounts.add(account(companyId, counterpartyId,
                    String.valueOf(counterparty.get("counterpartyName")), false, role));
        }
        return accounts;
    }

    public Map<String, Object> account(Long counterpartyCompanyId) { return account(counterpartyCompanyId, null); }

    public Map<String, Object> account(Long counterpartyCompanyId, String role) {
        long companyId = AuthContext.requireCompanyId();
        accessControlService.requirePermission(companyId, "reconciliation");
        requireRelation(companyId, counterpartyCompanyId);
        String name = identityDirectory != null ? identityDirectory.companyNames(List.of(counterpartyCompanyId)).get(counterpartyCompanyId)
                : jdbc.queryForObject("SELECT name FROM company WHERE id = ?", String.class,
                counterpartyCompanyId);
        return account(companyId, counterpartyCompanyId, name, true, role);
    }

    public WorkbookRespDTO workbook(Long counterpartyCompanyId) {
        long companyId = AuthContext.requireCompanyId();
        accessControlService.requirePermission(companyId, "reconciliation");
        requireRelation(companyId, counterpartyCompanyId);

        long companyAId = Math.min(companyId, counterpartyCompanyId);
        long companyBId = Math.max(companyId, counterpartyCompanyId);
        List<String> companyNames = identityDirectory != null ? identityDirectory.orderedCompanyNames(companyAId, companyBId) : jdbc.query("""
                        SELECT name FROM company WHERE id IN (?, ?) ORDER BY id
                        """, (rs, rowNum) -> rs.getString("name"), companyAId, companyBId);
        if (companyNames.size() != 2) throw new BusinessException("对账企业信息不完整");

        List<ContractAccount> contracts = contractDirectory != null ? ownedWorkbookContracts(companyAId, companyBId) : jdbc.query("""
                        SELECT contract.id, contract.contract_no, contract.amount,
                               COALESCE(contract.start_date, DATE(contract.approved_at),
                                        DATE(contract.created_at)) AS contract_date
                        FROM trade_contract contract
                        WHERE ((contract.company_id = ? AND contract.counterparty_company_id = ?)
                                OR (contract.company_id = ? AND contract.counterparty_company_id = ?))
                          AND EXISTS (
                              SELECT 1 FROM reconciliation_entry entry
                              WHERE entry.contract_id = contract.id
                                AND entry.company_a_id = ? AND entry.company_b_id = ?
                          )
                        ORDER BY contract_date, contract.id
                        """, (rs, rowNum) -> new ContractAccount(
                        rs.getLong("id"), rs.getString("contract_no"),
                        rs.getBigDecimal("amount"), rs.getObject("contract_date", LocalDate.class)),
                companyId, counterpartyCompanyId, counterpartyCompanyId, companyId,
                companyAId, companyBId);

        Map<Long, List<WorkbookEntry>> entriesByContract = new HashMap<>();
        for (ReconciliationEntryDO entry : entryMapper.selectWorkbookEntries(companyAId, companyBId)) {
            entriesByContract.computeIfAbsent(entry.getContractId(),
                    ignored -> new ArrayList<>()).add(new WorkbookEntry(
                    entry.getSourceType(), entry.getBusinessDate(), entry.getAmount()));
        }

        String title = companyNames.get(0) + "与" + companyNames.get(1) + "对账单";
        byte[] data = generateWorkbook(title, contracts, entriesByContract);
        return new WorkbookRespDTO(safeFileName(title) + ".xlsx", WORKBOOK_CONTENT_TYPE, data);
    }

    public void recordSalesOrder(BusinessDocumentRespDTO document, BigDecimal amount,
                                 LocalDate businessDate, long approvedBy,
                                 LocalDateTime approvedAt) {
        if (document == null || document.getId() == null || document.getRecipientCompanyId() == null) {
            throw new BusinessException("销售单对账信息不完整");
        }
        long supplierCompanyId = document.getSupplierCompanyId() == null
                ? document.getCompanyId() : document.getSupplierCompanyId();
        long buyerCompanyId = document.getBuyerCompanyId() == null
                ? document.getRecipientCompanyId() : document.getBuyerCompanyId();
        insertEntry(supplierCompanyId, buyerCompanyId,
                document.getContractId(), SALES_ORDER, document.getId(), businessDate,
                document.getDocumentNo(), amount, supplierCompanyId,
                buyerCompanyId, document.getCompanyId(), approvedBy, approvedAt);
    }

    public void recordReturnOrder(BusinessDocumentRespDTO document, BigDecimal amount,
                                  LocalDate businessDate, long approvedBy,
                                  LocalDateTime approvedAt) {
        if (document == null || document.getId() == null || document.getRecipientCompanyId() == null) {
            throw new BusinessException("退货单对账信息不完整");
        }
        long supplierCompanyId = document.getSupplierCompanyId() == null
                ? document.getRecipientCompanyId() : document.getSupplierCompanyId();
        long buyerCompanyId = document.getBuyerCompanyId() == null
                ? document.getCompanyId() : document.getBuyerCompanyId();
        // 退货冲减原销售额，例如 1000 元退货在对账中记为 -1000。
        BigDecimal negativeAmount = amount == null ? null : amount.abs().negate();
        insertEntry(supplierCompanyId, buyerCompanyId,
                document.getContractId(), RETURN_ORDER, document.getId(), businessDate,
                document.getDocumentNo(), negativeAmount, supplierCompanyId,
                buyerCompanyId, document.getCompanyId(), approvedBy, approvedAt);
    }

    public void recordAttachment(TradeContractRespDTO contract, String sourceType, long sourceId,
                                 LocalDate businessDate, String documentNo, BigDecimal amount,
                                 long issuerCompanyId, long approvedBy, LocalDateTime approvedAt) {
        if (contract == null || contract.getCounterpartyCompanyId() == null) {
            throw new BusinessException("附件对账合同信息不完整");
        }
        long supplierCompanyId = supplierCompanyId(contract);
        long buyerCompanyId = buyerCompanyId(contract);
        insertEntry(supplierCompanyId, buyerCompanyId, contract.getId(), sourceType, sourceId,
                businessDate, documentNo, amount, supplierCompanyId, buyerCompanyId,
                issuerCompanyId, approvedBy, approvedAt);
    }

    /**
     * 以追加负数流水的方式冲销已确认业务，原对账记录始终保留。
     */
    public void reverseSource(String sourceType, long sourceId, long actionRequestId,
                              long approvedBy, LocalDateTime approvedAt) {
        ReconciliationEntryDO source = entryMapper.selectReversalSource(sourceType, sourceId);
        if (source == null) return;
        ReconciliationEntryDO reversal = new ReconciliationEntryDO();
        reversal.setId(ApplicationIds.next());
        reversal.setCompanyAId(source.getCompanyAId());
        reversal.setCompanyBId(source.getCompanyBId());
        reversal.setContractId(source.getContractId());
        reversal.setSourceType(source.getSourceType() + "_VOID");
        reversal.setSourceId(actionRequestId);
        reversal.setBusinessDate(source.getBusinessDate());
        reversal.setDocumentNo(safe(source.getDocumentNo()) + "（作废冲销）");
        reversal.setAmount(source.getAmount().negate());
        reversal.setSupplierCompanyId(source.getSupplierCompanyId());
        reversal.setBuyerCompanyId(source.getBuyerCompanyId());
        reversal.setIssuerCompanyId(source.getIssuerCompanyId());
        reversal.setApprovedBy(approvedBy);
        reversal.setApprovedAt(approvedAt == null ? LocalDateTime.now() : approvedAt);
        reversal.setReversalOfId(source.getId());
        reversal.setActionRequestId(actionRequestId);
        entryMapper.upsertReversal(reversal);
    }

    private List<ContractAccount> ownedWorkbookContracts(long companyAId, long companyBId) {
        var ids = entryMapper.selectContractIds(companyAId, companyBId);
        return contractDirectory.contractsByIds(ids).stream().filter(contract ->
                (Long.valueOf(companyAId).equals(contract.getCompanyId()) && Long.valueOf(companyBId).equals(contract.getCounterpartyCompanyId()))
                || (Long.valueOf(companyBId).equals(contract.getCompanyId()) && Long.valueOf(companyAId).equals(contract.getCounterpartyCompanyId())))
                .map(contract -> new ContractAccount(contract.getId(), contract.getContractNo(), contract.getAmount(),
                        contract.getStartDate() != null ? contract.getStartDate()
                                : contract.getApprovedAt() != null ? contract.getApprovedAt().toLocalDate()
                                : contract.getCreatedAt() == null ? null : contract.getCreatedAt().toLocalDate()))
                .sorted(java.util.Comparator.comparing(ContractAccount::date, java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()))
                        .thenComparing(ContractAccount::id)).toList();
    }

    private void insertEntry(long leftCompanyId, long rightCompanyId, Long contractId,
                             String sourceType, Long sourceId, LocalDate businessDate,
                             String documentNo, BigDecimal amount, long supplierCompanyId,
                             long buyerCompanyId, long issuerCompanyId, long approvedBy,
                             LocalDateTime approvedAt) {
        if (contractId == null || sourceId == null || businessDate == null
                || amount == null || (amount.signum() < 0 && !RETURN_ORDER.equals(sourceType))
                || (RETURN_ORDER.equals(sourceType) && amount.signum() > 0)) {
            throw new BusinessException("单据对账金额或日期不完整");
        }
        long companyAId = Math.min(leftCompanyId, rightCompanyId);
        long companyBId = Math.max(leftCompanyId, rightCompanyId);
        ReconciliationEntryDO row = new ReconciliationEntryDO();
        row.setId(ApplicationIds.next());
        row.setCompanyAId(companyAId);
        row.setCompanyBId(companyBId);
        row.setContractId(contractId);
        row.setSourceType(sourceType);
        row.setSourceId(sourceId);
        row.setBusinessDate(businessDate);
        row.setDocumentNo(safe(documentNo));
        row.setAmount(money(amount));
        row.setSupplierCompanyId(supplierCompanyId);
        row.setBuyerCompanyId(buyerCompanyId);
        row.setIssuerCompanyId(issuerCompanyId);
        row.setApprovedBy(approvedBy);
        row.setApprovedAt(approvedAt == null ? LocalDateTime.now() : approvedAt);
        entryMapper.upsertEntry(row);
    }

    private Map<String, Object> account(long companyId, long counterpartyCompanyId,
                                        String counterpartyName, boolean includeEntries, String role) {
        long companyAId = Math.min(companyId, counterpartyCompanyId);
        long companyBId = Math.max(companyId, counterpartyCompanyId);
        Map<Long, String> contractNumbers = new HashMap<>();
        if (contractDirectory != null) {
            var ids = entryMapper.selectContractIds(companyAId, companyBId);
            contractDirectory.contractsByIds(ids).forEach(contract -> contractNumbers.put(contract.getId(), contract.getContractNo()));
        }
        List<ReconciliationEntryDO> rows = contractDirectory == null
                ? entryMapper.selectAccountEntriesWithContract(companyAId, companyBId)
                : entryMapper.selectAccountEntries(companyAId, companyBId);
        List<Entry> entries = rows.stream().map(row -> new Entry(
                        row.getId(), row.getContractId(),
                        row.getSourceType(), row.getSourceId(),
                        row.getBusinessDate(),
                        row.getDocumentNo(), row.getAmount(),
                        row.getSupplierCompanyId(), row.getBuyerCompanyId(),
                        row.getIssuerCompanyId(),
                        row.getApprovedAt(),
                        contractDirectory == null ? row.getContractNo() : contractNumbers.get(row.getContractId()))).toList();

        BigDecimal mySales = BigDecimal.ZERO;
        BigDecimal myPurchases = BigDecimal.ZERO;
        BigDecimal issuedInvoices = BigDecimal.ZERO;
        BigDecimal receivedInvoices = BigDecimal.ZERO;
        BigDecimal receivedPayments = BigDecimal.ZERO;
        BigDecimal paidPayments = BigDecimal.ZERO;
        LocalDateTime updatedAt = null;
        List<Map<String, Object>> detail = new ArrayList<>();
        int entryCount = 0;
        for (Entry entry : entries) {
            boolean mySale = entry.supplierCompanyId() == companyId;
            if ("buyer".equals(role) && mySale || "supplier".equals(role) && !mySale) continue;
            entryCount++;
            String sourceType = baseSourceType(entry.sourceType());
            if (SALES_ORDER.equals(sourceType) || RETURN_ORDER.equals(sourceType)) {
                if (mySale) mySales = mySales.add(entry.amount());
                else myPurchases = myPurchases.add(entry.amount());
            } else if (INVOICE.equals(sourceType)) {
                if (mySale) issuedInvoices = issuedInvoices.add(entry.amount());
                else receivedInvoices = receivedInvoices.add(entry.amount());
            } else if (PAYMENT_VOUCHER.equals(sourceType)) {
                if (mySale) receivedPayments = receivedPayments.add(entry.amount());
                else paidPayments = paidPayments.add(entry.amount());
            }
            if (updatedAt == null || entry.approvedAt().isAfter(updatedAt)) updatedAt = entry.approvedAt();
            if (includeEntries) detail.add(entryView(entry, mySale));
        }

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("counterpartyCompanyId", counterpartyCompanyId);
        view.put("counterpartyName", counterpartyName);
        view.put("mySalesAmount", money(mySales));
        view.put("myPurchaseAmount", money(myPurchases));
        view.put("issuedInvoiceAmount", money(issuedInvoices));
        view.put("receivedInvoiceAmount", money(receivedInvoices));
        view.put("receivedPaymentAmount", money(receivedPayments));
        view.put("paidPaymentAmount", money(paidPayments));
        view.put("receivableBalance", money(mySales.subtract(receivedPayments)));
        view.put("payableBalance", money(myPurchases.subtract(paidPayments)));
        view.put("unbilledAmount", money(mySales.subtract(issuedInvoices)));
        view.put("unreceivedInvoiceAmount", money(myPurchases.subtract(receivedInvoices)));
        view.put("entryCount", entryCount);
        view.put("updatedAt", updatedAt);
        if (includeEntries) view.put("entries", detail);
        return view;
    }

private Map<String, Object> entryView(Entry entry, boolean mySale) {
        Map<String, Object> view = new LinkedHashMap<>();
        String sourceType = baseSourceType(entry.sourceType());
        boolean reversal = !sourceType.equals(entry.sourceType());
        view.put("id", entry.id());
        view.put("contractId", entry.contractId());
        view.put("contractNo", safe(entry.contractNo()));
        view.put("sourceType", entry.sourceType());
        view.put("sourceTypeText", (reversal ? "作废冲销 · " : "") + switch (sourceType) {
            case SALES_ORDER -> "销售单";
            case RETURN_ORDER -> "退货单";
            case PAYMENT_VOUCHER -> "转款凭证";
            case INVOICE -> "发票";
            default -> entry.sourceType();
        });
        view.put("sourceId", entry.sourceId());
        view.put("businessDate", entry.businessDate());
        view.put("documentNo", entry.documentNo());
        view.put("amount", money(entry.amount()));
        view.put("direction", mySale ? "SALE" : "PURCHASE");
        view.put("directionText", mySale ? "我方销售" : "我方采购");
        view.put("approvedAt", entry.approvedAt());
        return view;
    }

    private static String baseSourceType(String sourceType) {
        return sourceType != null && sourceType.endsWith("_VOID")
                ? sourceType.substring(0, sourceType.length() - 5) : sourceType;
    }

    private void requireRelation(long companyId, Long counterpartyCompanyId) {
        if (counterpartyCompanyId == null || companyId == counterpartyCompanyId) {
            throw new BusinessException("请选择合作企业");
        }
        if (relationMapper.countActiveBetween(companyId, counterpartyCompanyId) == 0) {
            throw new BusinessException("合作企业关系不存在");
        }
    }

    public byte[] generateWorkbook(String title, List<ContractAccount> contracts,
                            Map<Long, List<WorkbookEntry>> entriesByContract) {
        try (InputStream input = new ClassPathResource(WORKBOOK_TEMPLATE).getInputStream();
             XSSFWorkbook workbook = new XSSFWorkbook(input);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.getSheetAt(0);
            int requiredRows = contracts.stream()
                    .mapToInt(contract -> rowsForContract(entriesByContract.get(contract.id())))
                    .sum();
            int dataRows = Math.max(TEMPLATE_DATA_ROWS, requiredRows);
            int totalRowIndex = DATA_START_ROW + dataRows;
            if (dataRows > TEMPLATE_DATA_ROWS) {
                int extraRows = dataRows - TEMPLATE_DATA_ROWS;
                sheet.shiftRows(TEMPLATE_TOTAL_ROW, sheet.getLastRowNum(), extraRows, true, false);
                Row styleSource = sheet.getRow(TEMPLATE_TOTAL_ROW - 1);
                for (int rowIndex = TEMPLATE_TOTAL_ROW; rowIndex < totalRowIndex; rowIndex++) {
                    copyRowStyle(styleSource, sheet.createRow(rowIndex));
                }
            }

            cell(sheet.getRow(3), 2).setCellValue(title);
            clearDataRows(sheet, DATA_START_ROW, totalRowIndex);
            int rowIndex = DATA_START_ROW;
            for (ContractAccount contract : contracts) {
                List<WorkbookEntry> entries = entriesByContract.getOrDefault(contract.id(), List.of());
                List<WorkbookEntry> sales = entries.stream()
                        .filter(entry -> SALES_ORDER.equals(baseSourceType(entry.sourceType()))
                                || RETURN_ORDER.equals(baseSourceType(entry.sourceType())))
                        .toList();
                List<WorkbookEntry> payments = entriesOfType(entries, PAYMENT_VOUCHER);
                List<WorkbookEntry> invoices = entriesOfType(entries, INVOICE);
                int contractRows = Math.max(1, Math.max(sales.size(), Math.max(payments.size(), invoices.size())));
                Row firstRow = sheet.getRow(rowIndex);
                if (contract.date() != null) {
                    Cell dateCell = cell(firstRow, 2);
                    dateCell.setCellStyle(cell(sheet.getRow(DATA_START_ROW), 2).getCellStyle());
                    dateCell.setCellValue(contract.date());
                }
                cell(firstRow, 3).setCellValue(safe(contract.contractNo()));
                setMoney(cell(firstRow, 4), contract.amount());
                for (int offset = 0; offset < contractRows; offset++) {
                    Row row = sheet.getRow(rowIndex + offset);
                    if (offset < sales.size()) setMoney(cell(row, 5), sales.get(offset).amount());
                    if (offset < payments.size()) setMoney(cell(row, 6), payments.get(offset).amount());
                    if (offset < invoices.size()) setMoney(cell(row, 8), invoices.get(offset).amount());
                }
                int firstExcelRow = rowIndex + 1;
                int lastExcelRow = rowIndex + contractRows;
                cell(firstRow, 7).setCellFormula("SUM(F" + firstExcelRow + ":F" + lastExcelRow
                        + ")-SUM(G" + firstExcelRow + ":G" + lastExcelRow + ")");
                cell(firstRow, 9).setCellFormula("SUM(F" + firstExcelRow + ":F" + lastExcelRow
                        + ")-SUM(I" + firstExcelRow + ":I" + lastExcelRow + ")");
                rowIndex += contractRows;
            }

            Row totalRow = sheet.getRow(totalRowIndex);
            cell(totalRow, 2).setCellValue("合计");
            cell(totalRow, 3).setBlank();
            int firstDataExcelRow = DATA_START_ROW + 1;
            int lastDataExcelRow = totalRowIndex;
            for (int column = 4; column <= 9; column++) {
                String letter = String.valueOf((char) ('A' + column));
                cell(totalRow, column).setCellFormula("SUM(" + letter + firstDataExcelRow
                        + ":" + letter + lastDataExcelRow + ")");
            }
            workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
            sheet.setForceFormulaRecalculation(true);
            workbook.setForceFormulaRecalculation(true);
            workbook.write(output);
            return output.toByteArray();
        } catch (Exception exception) {
            throw new BusinessException("自动对账单生成失败，请稍后重试");
        }
    }

    private int rowsForContract(List<WorkbookEntry> entries) {
        if (entries == null || entries.isEmpty()) return 1;
        int sales = 0;
        int payments = 0;
        int invoices = 0;
        for (WorkbookEntry entry : entries) {
            String sourceType = baseSourceType(entry.sourceType());
            if (SALES_ORDER.equals(sourceType) || RETURN_ORDER.equals(sourceType)) sales++;
            else if (PAYMENT_VOUCHER.equals(sourceType)) payments++;
            else if (INVOICE.equals(sourceType)) invoices++;
        }
        return Math.max(1, Math.max(sales, Math.max(payments, invoices)));
    }

    private List<WorkbookEntry> entriesOfType(List<WorkbookEntry> entries, String sourceType) {
        return entries.stream().filter(entry -> sourceType.equals(baseSourceType(entry.sourceType()))).toList();
    }

    private void clearDataRows(Sheet sheet, int startRow, int endRow) {
        for (int rowIndex = startRow; rowIndex < endRow; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) row = sheet.createRow(rowIndex);
            for (int column = 2; column <= 9; column++) cell(row, column).setBlank();
        }
    }

    private void copyRowStyle(Row source, Row target) {
        target.setHeight(source.getHeight());
        for (int column = 0; column <= 9; column++) {
            Cell sourceCell = source.getCell(column, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
            Cell targetCell = target.createCell(column, CellType.BLANK);
            targetCell.setCellStyle(sourceCell.getCellStyle());
        }
    }

    private Cell cell(Row row, int column) {
        return row.getCell(column, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
    }

    private void setMoney(Cell cell, BigDecimal value) {
        cell.setCellValue(money(value).doubleValue());
    }

    private String safeFileName(String value) {
        return safe(value).replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_");
    }

    private long supplierCompanyId(TradeContractRespDTO contract) {
        if ("PURCHASE".equalsIgnoreCase(contract.getDirection())) {
            return contract.getCounterpartyCompanyId();
        }
        return contract.getCompanyId();
    }

    private long buyerCompanyId(TradeContractRespDTO contract) {
        return "PURCHASE".equalsIgnoreCase(contract.getDirection())
                ? contract.getCompanyId() : contract.getCounterpartyCompanyId();
    }

    private BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private record Entry(Long id, Long contractId, String sourceType, Long sourceId,
                         LocalDate businessDate, String documentNo, BigDecimal amount,
                         long supplierCompanyId, long buyerCompanyId, long issuerCompanyId,
                         LocalDateTime approvedAt, String contractNo) {
    }

    private record ReversalSource(long id, long companyAId, long companyBId, long contractId,
                                  String sourceType, LocalDate businessDate, String documentNo,
                                  BigDecimal amount, long supplierCompanyId, long buyerCompanyId,
                                  long issuerCompanyId) {
    }

    

    

    
}
