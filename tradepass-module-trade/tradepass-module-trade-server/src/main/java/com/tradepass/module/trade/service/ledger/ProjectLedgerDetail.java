package com.tradepass.module.trade.service.ledger;

import com.tradepass.module.contract.api.contract.dto.TradeContractRespDTO;
import com.tradepass.module.settlement.api.reconciliation.ReconciliationAccountOperations.ProjectLedgerEntry;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/** The project template uses payments minus invoices for the unbilled column. */
final class ProjectLedgerDetail {
    static final List<String> COLUMNS = List.of("日期", "名称", "合同金额", "金额", "转款", "未转款", "已开发票", "未开发票");

    private ProjectLedgerDetail() { }

    static Map<String, Object> build(Map<String, Object> project, long companyId,
                                     List<TradeContractRespDTO> contracts, Map<Long, String> companyNames,
                                     List<ProjectLedgerEntry> entries) {
        Map<Long, TradeContractRespDTO> eligible = new LinkedHashMap<>();
        Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
        Map<Long, String> contractGroups = new HashMap<>();
        for (TradeContractRespDTO contract : contracts) {
            boolean initiator = Objects.equals(companyId, contract.getCompanyId());
            boolean counterparty = Objects.equals(companyId, contract.getCounterpartyCompanyId());
            if ((!initiator && !counterparty) || !"ACTIVE".equals(contract.getStatus())) continue;
            String direction = initiator ? contract.getDirection()
                    : "PURCHASE".equalsIgnoreCase(contract.getDirection()) ? "SALE" : "PURCHASE";
            direction = direction == null ? "" : direction.toUpperCase(Locale.ROOT);
            if (!Set.of("SALE", "PURCHASE").contains(direction)) continue;
            Long otherId = initiator ? contract.getCounterpartyCompanyId() : contract.getCompanyId();
            String otherName = initiator ? contract.getCounterpartyName() : companyNames.get(otherId);
            String key = (otherId == null ? "name:" + Objects.toString(otherName, "") : otherId.toString()) + ":" + direction;
            Map<String, Object> group = groups.get(key);
            if (group == null) {
                group = new LinkedHashMap<>();
                group.put("key", key);
                group.put("counterpartyCompanyId", otherId);
                group.put("counterpartyName", Objects.toString(otherName, "未填写往来公司"));
                group.put("direction", direction);
                group.put("directionText", "PURCHASE".equals(direction) ? "采购" : "销售");
                group.put("rows", new ArrayList<Map<String, Object>>());
                groups.put(key, group);
            }
            eligible.put(contract.getId(), contract);
            contractGroups.put(contract.getId(), key);
            LocalDate date = contract.getStartDate() != null ? contract.getStartDate()
                    : contract.getApprovedAt() != null ? contract.getApprovedAt().toLocalDate()
                    : contract.getCreatedAt() == null ? null : contract.getCreatedAt().toLocalDate();
            rows(group).add(row("contract:" + contract.getId(), contract.getId(), contract.getContractNo(),
                    "合同", "CONTRACT", date, contract.getContractNo(), contract.getApprovedAt(),
                    money(contract.getAmount()), null, null, null));
        }
        Set<Long> seenEntries = new HashSet<>();
        for (ProjectLedgerEntry entry : entries) {
            TradeContractRespDTO contract = eligible.get(entry.contractId());
            if (contract == null || !seenEntries.add(entry.id())) continue;
            String type = Objects.toString(entry.sourceType(), "");
            boolean reversal = type.endsWith("_VOID");
            String base = reversal ? type.substring(0, type.length() - 5) : type;
            String name = switch (base) {
                case "SALES_ORDER" -> "销售单";
                case "RETURN_ORDER" -> "退货单";
                case "PAYMENT_VOUCHER" -> "转款单";
                case "INVOICE" -> "发票";
                default -> null;
            };
            if (name == null) continue;
            if (reversal) name += "（作废冲销）";
            BigDecimal amount = money(entry.amount());
            rows(groups.get(contractGroups.get(entry.contractId()))).add(row("entry:" + entry.id(),
                    entry.contractId(), contract.getContractNo(), name, type, entry.businessDate(),
                    entry.documentNo(), entry.approvedAt(), null,
                    Set.of("SALES_ORDER", "RETURN_ORDER").contains(base) ? amount : null,
                    "PAYMENT_VOUCHER".equals(base) ? amount : null, "INVOICE".equals(base) ? amount : null));
        }
        for (Map<String, Object> group : groups.values()) {
            List<Map<String, Object>> rows = rows(group);
            rows.sort(Comparator.comparing((Map<String, Object> row) -> (LocalDate) row.get("date"),
                            Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(row -> "CONTRACT".equals(row.get("sourceType")) ? 0 : 1)
                    .thenComparing(row -> (LocalDateTime) row.get("approvedAt"), Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(row -> (Long) row.get("sortId")));
            BigDecimal contractAmount = money(null), amount = money(null), payment = money(null), invoice = money(null);
            for (Map<String, Object> row : rows) {
                contractAmount = contractAmount.add(number(row.get("contractAmount")));
                amount = amount.add(number(row.get("amount")));
                payment = payment.add(number(row.get("paymentAmount")));
                invoice = invoice.add(number(row.get("invoiceAmount")));
                if (!"CONTRACT".equals(row.get("sourceType"))) {
                    row.put("unpaidAmount", amount.subtract(payment));
                    row.put("unbilledAmount", payment.subtract(invoice));
                }
                row.remove("approvedAt");
                row.remove("sortId");
            }
            Map<String, Object> totals = new LinkedHashMap<>();
            totals.put("contractAmount", contractAmount);
            totals.put("amount", amount);
            totals.put("paymentAmount", payment);
            totals.put("unpaidAmount", amount.subtract(payment));
            totals.put("invoiceAmount", invoice);
            totals.put("unbilledAmount", payment.subtract(invoice));
            group.put("totals", totals);
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("projectId", project.get("id"));
        detail.put("projectNo", project.get("projectNo"));
        detail.put("projectName", project.get("name"));
        detail.put("title", project.get("name") + "台账明细");
        detail.put("asOfDate", LocalDate.now());
        detail.put("columns", COLUMNS);
        detail.put("groups", new ArrayList<>(groups.values()));
        detail.put("contractCount", eligible.size());
        detail.put("excludedContractCount", contracts.size() - eligible.size());
        return detail;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rows(Map<String, Object> group) {
        return (List<Map<String, Object>>) group.get("rows");
    }

    private static Map<String, Object> row(String key, Long contractId, String contractNo, String name,
                                           String type, LocalDate date, String documentNo, LocalDateTime approvedAt,
                                           BigDecimal contractAmount, BigDecimal amount,
                                           BigDecimal paymentAmount, BigDecimal invoiceAmount) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", key);
        row.put("sortId", Long.parseLong(key.substring(key.indexOf(':') + 1)));
        row.put("contractId", contractId);
        row.put("contractNo", Objects.toString(contractNo, ""));
        row.put("name", name);
        row.put("sourceType", type);
        row.put("date", date);
        row.put("documentNo", Objects.toString(documentNo, ""));
        row.put("approvedAt", approvedAt);
        row.put("contractAmount", contractAmount);
        row.put("amount", amount);
        row.put("paymentAmount", paymentAmount);
        row.put("unpaidAmount", null);
        row.put("invoiceAmount", invoiceAmount);
        row.put("unbilledAmount", null);
        return row;
    }

    private static BigDecimal number(Object value) { return value instanceof BigDecimal amount ? amount : money(null); }
    private static BigDecimal money(BigDecimal value) { return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP); }
}
