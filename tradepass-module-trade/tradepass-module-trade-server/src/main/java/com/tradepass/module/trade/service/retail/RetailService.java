package com.tradepass.module.trade.service.retail;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradepass.framework.audit.core.AuditLogService;
import com.tradepass.framework.mybatis.core.ApplicationIds;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.common.pojo.PagePayload;
import com.tradepass.module.identity.api.company.CompanyReader;
import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.module.identity.api.user.UserIdentityOperations;
import com.tradepass.module.trade.dal.dataobject.document.BusinessDocumentDO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import static com.tradepass.module.trade.service.retail.RetailDtos.*;

@Service
public class RetailService {
    private final JdbcTemplate jdbc;
    private final AccessControlOperations access;
    private final CompanyReader companies;
    private final UserIdentityOperations users;
    private final AuditLogService audit;
    private final ObjectMapper json;
    public RetailService(JdbcTemplate jdbc, AccessControlOperations access, CompanyReader companies,
                         UserIdentityOperations users, AuditLogService audit, ObjectMapper json) {
        this.jdbc = jdbc; this.access = access; this.companies = companies;
        this.users = users; this.audit = audit; this.json = json;
    }

    public Map<String, Object> customers(int page, int size) {
        long cid = readCompany();
        page = Math.max(1, page); size = Math.max(1, Math.min(100, size));
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM retail_customer WHERE company_id = ?", Long.class, cid);
        var rows = jdbc.queryForList("""
                SELECT c.*, COALESCE(t.sales_amount, 0) AS sales_amount,
                       COALESCE(t.return_amount, 0) AS return_amount, COALESCE(t.sales_count, 0) AS sales_count
                FROM retail_customer c LEFT JOIN (
                    SELECT customer_id,
                        SUM(CASE WHEN document_type = 'SALES_ORDER' THEN amount ELSE 0 END) AS sales_amount,
                        SUM(CASE WHEN document_type = 'RETURN_ORDER' THEN amount ELSE 0 END) AS return_amount,
                        SUM(CASE WHEN document_type = 'SALES_ORDER' THEN 1 ELSE 0 END) AS sales_count
                    FROM retail_document WHERE company_id = ? AND status = 'CONFIRMED' GROUP BY customer_id
                ) t ON t.customer_id = c.id WHERE c.company_id = ?
                ORDER BY sales_amount DESC, c.created_at DESC, c.id DESC LIMIT ? OFFSET ?
                """, cid, cid, size, (long) (page - 1) * size);
        var result = new LinkedHashMap<String, Object>();
        result.put("items", rows.stream().map(this::customerView).toList());
        result.put("total", total); result.put("hasMore", (long) page * size < Objects.requireNonNull(total));
        result.put("canCreate", access.hasPermission(cid, "order_create"));
        return result;
    }

    public Map<String, Object> customer(Long id) {
        long cid = readCompany();
        var row = requireCustomer(cid, id, false);
        var totals = jdbc.queryForMap("""
                SELECT COALESCE(SUM(CASE WHEN document_type = 'SALES_ORDER' THEN amount ELSE 0 END), 0) AS sales_amount,
                       COALESCE(SUM(CASE WHEN document_type = 'RETURN_ORDER' THEN amount ELSE 0 END), 0) AS return_amount,
                       COALESCE(SUM(CASE WHEN document_type = 'SALES_ORDER' THEN 1 ELSE 0 END), 0) AS sales_count
                FROM retail_document WHERE company_id = ? AND customer_id = ? AND status = 'CONFIRMED'
                """, cid, id);
        row.putAll(totals);
        var result = customerView(row);
        result.put("canEdit", access.hasPermission(cid, "order_create"));
        result.put("canManageStock", access.hasPermission(cid, "inventory_receive"));
        return result;
    }

    @Transactional
    public Map<String, Object> saveCustomer(Long id, CustomerRequest request) {
        long cid = writeCompany();
        String type = text(request.customerType(), 32, "客户类型");
        if (!List.of("COMPANY", "INDIVIDUAL_BUSINESS", "PERSON").contains(type))
            throw new BusinessException("请选择企业、个体工商户或个人");
        String name = required(request.name(), 128, "客户名称");
        String contact = text(request.contact(), 64, "联系人"), phone = text(request.phone(), 64, "联系电话");
        String address = text(request.address(), 256, "收货地址"), invoice = text(request.invoiceTitle(), 128, "开票抬头");
        String tax = text(request.taxNo(), 64, "税号"), remark = text(request.remark(), 500, "备注");
        if (id == null) {
            id = ApplicationIds.next();
            jdbc.update("""
                    INSERT INTO retail_customer (id, company_id, customer_type, name, contact, phone, address,
                        invoice_title, tax_no, remark, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, cid, type, name, contact, phone, address, invoice, tax, remark, AuthContext.userId());
        } else {
            requireCustomer(cid, id, true);
            jdbc.update("""
                    UPDATE retail_customer SET customer_type = ?, name = ?, contact = ?, phone = ?, address = ?,
                        invoice_title = ?, tax_no = ?, remark = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND company_id = ?
                    """, type, name, contact, phone, address, invoice, tax, remark, id, cid);
        }
        audit.log(cid, "RETAIL_CUSTOMER", id, "SAVE", "保存零售客户 " + name);
        return customer(id);
    }

    public PagePayload<Map<String, Object>> documents(Long customerId, String type, String status, int page, int size) {
        long cid = readCompany(); requireCustomer(cid, customerId, false);
        type = documentType(type);
        if (status != null && !status.isBlank() && !List.of("DRAFT", "CONFIRMED").contains(status))
            throw new BusinessException("单据状态不正确");
        page = Math.max(1, page); size = Math.max(1, Math.min(100, size));
        String filter = " WHERE company_id = ? AND customer_id = ? AND document_type = ?";
        List<Object> args = new ArrayList<>(List.of(cid, customerId, type));
        if (status != null && !status.isBlank()) { filter += " AND status = ?"; args.add(status); }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM retail_document" + filter, Long.class, args.toArray());
        args.add(size); args.add((long) (page - 1) * size);
        var rows = jdbc.queryForList("SELECT * FROM retail_document" + filter
                + " ORDER BY order_date DESC, id DESC LIMIT ? OFFSET ?", args.toArray());
        return PagePayload.of(rows.stream().map(this::documentView).toList(), total == null ? 0 : total, page, size);
    }

    public Map<String, Object> document(Long id) {
        long cid = readCompany();
        var row = requireDocument(cid, id, false);
        var result = documentView(row);
        var itemRows = items(id);
        for (var item : itemRows) {
            long itemId = number(item.get("id"));
            BigDecimal returned = returnedQuantity(itemId, null);
            item.put("returnedQuantity", returned.toPlainString());
            item.put("returnedAmount", returnedAmount(itemId, null).toPlainString());
            item.put("returnableQuantity", decimal(item.get("quantity")).subtract(returned).toPlainString());
            item.put("roundingAdjustment", decimal(item.get("amount")).subtract(decimal(item.get("quantity"))
                    .multiply(decimal(item.get("unitPrice"))).setScale(2, RoundingMode.HALF_UP)).toPlainString());
        }
        result.put("items", itemRows);
        result.put("customer", decode(String.valueOf(row.get("customer_snapshot"))));
        result.put("companyName", row.get("company_name"));
        result.put("preparedByName", row.get("prepared_by_name"));
        result.put("remark", row.get("remark"));
        result.put("canEdit", "DRAFT".equals(row.get("status")) && access.hasPermission(cid, "order_create"));
        result.put("canProcessStock", "CONFIRMED".equals(row.get("status"))
                && row.get("stock_processed_at") == null && access.hasPermission(cid, "inventory_receive"));
        result.put("canCreateReturn", "CONFIRMED".equals(row.get("status")) && "SALES_ORDER".equals(row.get("document_type"))
                && access.hasPermission(cid, "order_create") && itemRows.stream().anyMatch(i -> decimal(i.get("returnableQuantity")).signum() > 0));
        if (row.get("original_document_id") != null) {
            var original = requireDocument(cid, number(row.get("original_document_id")), false);
            result.put("originalDocumentNo", original.get("document_no"));
        }
        return result;
    }

    @Transactional
    public Map<String, Object> createDocument(Long customerId, DocumentRequest request) {
        long cid = writeCompany();
        var customer = requireCustomer(cid, customerId, true);
        String requestId = required(request.requestId(), 64, "请求标识");
        if (!requestId.matches("[a-zA-Z0-9-]{16,64}")) throw new BusinessException("请求标识无效，请重新打开开单页面");
        String type = documentType(request.documentType());
        var existing = jdbc.queryForList("SELECT * FROM retail_document WHERE company_id = ? AND request_id = ?", cid, requestId);
        if (!existing.isEmpty()) {
            var row = existing.get(0);
            if (number(row.get("customer_id")) != customerId || !type.equals(row.get("document_type")))
                throw new BusinessException("请求标识已被其他单据使用");
            // A lost create response must not cause a later edited form to confirm stale prices.
            return "DRAFT".equals(row.get("status")) ? updateDraft(number(row.get("id")), request)
                    : document(number(row.get("id")));
        }
        var normalized = normalizeItems(cid, customerId, type, request, null);
        long id = ApplicationIds.next();
        var company = companies.selectById(cid);
        if (company == null) throw new BusinessException("当前企业不存在");
        jdbc.update("""
                INSERT INTO retail_document (id, company_id, customer_id, request_id, document_no, document_type,
                    original_document_id, order_date, amount, customer_snapshot, company_name, prepared_by_name, remark, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, cid, customerId, requestId, ("RETURN_ORDER".equals(type) ? "LSTH-" : "LSXS-") + id,
                type, "RETURN_ORDER".equals(type) ? request.originalDocumentId() : null, date(request.orderDate()),
                total(normalized), encode(customerView(customer)), company.getName(), users.currentDisplayName(),
                text(request.remark(), 500, "备注"), AuthContext.userId());
        insertItems(id, normalized);
        audit.log(cid, "RETAIL_DOCUMENT", id, "CREATE", "创建零售" + label(type) + "草稿");
        return document(id);
    }

    @Transactional
    public Map<String, Object> updateDraft(Long id, DocumentRequest request) {
        long cid = writeCompany(); var row = requireDocument(cid, id, true);
        if (!"DRAFT".equals(row.get("status"))) throw new BusinessException("只有草稿可以修改");
        if (!Objects.equals(request.documentType(), row.get("document_type"))) throw new BusinessException("不能修改单据类型");
        var normalized = normalizeItems(cid, number(row.get("customer_id")), request.documentType(), request, id);
        jdbc.update("""
                UPDATE retail_document SET order_date = ?, amount = ?, remark = ?, original_document_id = ?, customer_snapshot = ?,
                    updated_at = CURRENT_TIMESTAMP WHERE id = ? AND company_id = ?
                """, date(request.orderDate()), total(normalized), text(request.remark(), 500, "备注"),
                "RETURN_ORDER".equals(request.documentType()) ? request.originalDocumentId() : null,
                encode(customerView(requireCustomer(cid, number(row.get("customer_id")), false))), id, cid);
        jdbc.update("DELETE FROM retail_document_item WHERE document_id = ?", id);
        insertItems(id, normalized);
        audit.log(cid, "RETAIL_DOCUMENT", id, "UPDATE", "修改零售单据草稿");
        return document(id);
    }

    @Transactional
    public Map<String, Object> confirm(Long id, Long warehouseId) {
        long cid = writeCompany(); var row = requireDocument(cid, id, true);
        if ("CONFIRMED".equals(row.get("status"))) return document(id);
        if (!"DRAFT".equals(row.get("status"))) throw new BusinessException("当前单据不能确认");
        if ("RETURN_ORDER".equals(row.get("document_type"))) {
            var original = requireOriginal(cid, number(row.get("customer_id")), number(row.get("original_document_id")));
            var originals = items(number(original.get("id")));
            for (var item : items(id)) {
                long originalId = number(item.get("originalItemId"));
                var source = originals.stream().filter(i -> number(i.get("id")) == originalId).findFirst()
                        .orElseThrow(() -> new BusinessException("原销售商品不存在"));
                ensureReturnQuantity(source, decimal(item.get("quantity")), id);
                if (refundAmount(source, decimal(item.get("quantity")), id).compareTo(decimal(item.get("amount"))) != 0)
                    throw new BusinessException("原销售单已有新退货，请编辑刷新草稿金额后再确认");
            }
        }
        if (warehouseId != null) processStock(cid, row, warehouseId);
        jdbc.update("""
                UPDATE retail_document SET status = 'CONFIRMED', confirmed_by = ?, confirmed_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP WHERE id = ? AND company_id = ?
                """, AuthContext.userId(), id, cid);
        audit.log(cid, "RETAIL_DOCUMENT", id, "CONFIRM", "本公司确认零售" + label(String.valueOf(row.get("document_type"))));
        return document(id);
    }

    @Transactional
    public Map<String, Object> processStock(Long id, Long warehouseId) {
        long cid = readCompany(); var row = requireDocument(cid, id, true);
        if (!"CONFIRMED".equals(row.get("status"))) throw new BusinessException("请先确认单据");
        processStock(cid, row, warehouseId);
        return document(id);
    }

    @Transactional
    public String deleteDraft(Long id) {
        long cid = writeCompany(); var row = requireDocument(cid, id, true);
        if (!"DRAFT".equals(row.get("status"))) throw new BusinessException("已生效单据不能删除");
        jdbc.update("DELETE FROM retail_document_item WHERE document_id = ?", id);
        jdbc.update("DELETE FROM retail_document WHERE id = ? AND company_id = ?", id, cid);
        audit.log(cid, "RETAIL_DOCUMENT", id, "DELETE", "删除零售单据草稿");
        return "草稿已删除";
    }

    public Map<String, Object> summary(long companyId, String period) {
        access.resolveCompanyId(String.valueOf(companyId));
        LocalDate now = LocalDate.now();
        LocalDate from, to;
        switch (period) {
            case "month" -> { from = now.withDayOfMonth(1); to = from.plusMonths(1); }
            case "last12" -> { from = now.withDayOfMonth(1).minusMonths(11); to = now.withDayOfMonth(1).plusMonths(1); }
            default -> { from = now.withDayOfYear(1); to = from.plusYears(1); }
        }
        var row = jdbc.queryForMap("""
                SELECT COALESCE(SUM(CASE WHEN document_type = 'SALES_ORDER' THEN amount ELSE 0 END), 0) AS salesAmount,
                       COALESCE(SUM(CASE WHEN document_type = 'RETURN_ORDER' THEN amount ELSE 0 END), 0) AS returnAmount,
                       COALESCE(SUM(CASE WHEN document_type = 'SALES_ORDER' THEN 1 ELSE 0 END), 0) AS salesOrderCount
                FROM retail_document WHERE company_id = ? AND status = 'CONFIRMED' AND order_date >= ? AND order_date < ?
                """, companyId, from, to);
        row.put("customerCount", jdbc.queryForObject("SELECT COUNT(*) FROM retail_customer WHERE company_id = ?", Long.class, companyId));
        return row;
    }

    public BusinessDocumentDO pdfDocument(Long id) {
        var detail = document(id);
        var customer = (Map<?, ?>) detail.get("customer");
        List<List<String>> rows = new ArrayList<>(); int index = 1;
        for (var item : items(id)) rows.add(List.of(String.valueOf(index++), String.valueOf(item.get("productName")),
                String.valueOf(item.get("specification")), String.valueOf(item.get("baseUnit")),
                String.valueOf(item.get("quantity")), String.valueOf(item.get("unitPrice")),
                String.valueOf(item.get("amount")), String.valueOf(item.get("remark"))
                    + (decimal(item.get("amount")).compareTo(decimal(item.get("quantity")).multiply(decimal(item.get("unitPrice")))
                            .setScale(2, RoundingMode.HALF_UP)) != 0 ? "（含累计舍入尾差）" : "")));
        var content = new LinkedHashMap<String, Object>();
        content.put("title", "零售" + label(String.valueOf(detail.get("documentType"))));
        content.put("companyName", detail.get("companyName")); content.put("counterpartyName", customer.get("name"));
        content.put("contact", customer.get("contact")); content.put("phone", customer.get("phone")); content.put("address", customer.get("address"));
        content.put("contractNo", detail.getOrDefault("originalDocumentNo", "零售业务"));
        content.put("date", detail.get("orderDate")); content.put("totalAmount", detail.get("amount"));
        content.put("preparedByName", detail.get("preparedByName")); content.put("remark", detail.get("remark"));
        content.put("columns", List.of("序号", "品名", "规格", "单位", "数量", "单价", "金额", "备注"));
        content.put("rows", rows); content.put("blankRows", Math.max(8, rows.size()));
        BusinessDocumentDO document = new BusinessDocumentDO();
        document.setId(id); document.setCompanyId(AuthContext.requireCompanyId());
        document.setDocumentNo(String.valueOf(detail.get("documentNo"))); document.setDocumentType(String.valueOf(detail.get("documentType")));
        document.setSourceType("RETAIL"); document.setStatus(String.valueOf(detail.get("status"))); document.setContent(encode(content));
        return document;
    }

    private void processStock(long cid, Map<String, Object> document, Long warehouseId) {
        access.requirePermission(cid, "inventory_receive");
        if (document.get("stock_processed_at") != null) return;
        if (warehouseId == null) throw new BusinessException("请选择仓库");
        var warehouses = jdbc.queryForList("SELECT id FROM warehouse WHERE id = ? AND company_id = ? AND enabled = 1 FOR UPDATE", warehouseId, cid);
        if (warehouses.isEmpty()) throw new BusinessException("仓库不存在或已停用");
        long documentId = number(document.get("id"));
        boolean returning = "RETURN_ORDER".equals(document.get("document_type"));
        var products = items(documentId).stream().filter(i -> "PRODUCT".equals(i.get("lineType"))).toList();
        if (products.isEmpty()) throw new BusinessException("该单据没有需要处理库存的商品");
        for (var item : products) {
            String name = String.valueOf(item.get("productName")), spec = String.valueOf(item.get("specification")), unit = String.valueOf(item.get("baseUnit"));
            var ids = jdbc.queryForList("SELECT id FROM inventory_product WHERE company_id = ? AND product_name = ? AND specification = ? AND base_unit = ?", Long.class, cid, name, spec, unit);
            long productId;
            if (ids.isEmpty()) {
                if (!returning) throw new BusinessException(name + "没有库存，请先入库或选择暂不出库");
                jdbc.update("""
                        INSERT INTO inventory_product (id, company_id, product_name, specification, base_unit) VALUES (?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE id = id
                        """, ApplicationIds.next(), cid, name, spec, unit);
                productId = Objects.requireNonNull(jdbc.queryForObject("SELECT id FROM inventory_product WHERE company_id = ? AND product_name = ? AND specification = ? AND base_unit = ?", Long.class, cid, name, spec, unit));
            } else productId = ids.get(0);
            jdbc.update("""
                    INSERT INTO inventory_balance (id, company_id, warehouse_id, product_id, quantity, unit_price, inventory_amount)
                    VALUES (?, ?, ?, ?, 0, 0, 0) ON DUPLICATE KEY UPDATE id = id
                    """, ApplicationIds.next(), cid, warehouseId, productId);
            var balance = jdbc.queryForMap("SELECT quantity, unit_price, inventory_amount FROM inventory_balance WHERE company_id = ? AND warehouse_id = ? AND product_id = ? FOR UPDATE", cid, warehouseId, productId);
            BigDecimal quantity = decimal(item.get("quantity")), current = decimal(balance.get("quantity")), value = decimal(balance.get("inventory_amount"));
            BigDecimal cost = current.signum() == 0 ? decimal(balance.get("unit_price")) : value.divide(current, 6, RoundingMode.HALF_UP);
            if (returning) {
                var originalCost = jdbc.queryForList("SELECT inventory_unit_cost, unit_price FROM retail_document_item WHERE id = ?", item.get("originalItemId"));
                if (originalCost.isEmpty()) throw new BusinessException("原销售商品不存在");
                if (originalCost.get(0).get("inventory_unit_cost") == null)
                    throw new BusinessException("请先完成原销售单出库，或选择暂不入库");
                cost = decimal(originalCost.get(0).get("inventory_unit_cost"));
            } else if (current.compareTo(quantity) < 0) throw new BusinessException(name + "库存不足，请先处理库存");
            BigDecimal delta = returning ? quantity : quantity.negate();
            BigDecimal next = current.add(delta), nextValue = value.add(cost.multiply(delta).setScale(2, RoundingMode.HALF_UP));
            if (next.signum() == 0) nextValue = BigDecimal.ZERO;
            BigDecimal nextPrice = next.signum() == 0 ? BigDecimal.ZERO : nextValue.divide(next, 6, RoundingMode.HALF_UP);
            jdbc.update("UPDATE inventory_balance SET quantity = ?, unit_price = ?, inventory_amount = ?, updated_at = CURRENT_TIMESTAMP WHERE company_id = ? AND warehouse_id = ? AND product_id = ?", next, nextPrice, nextValue, cid, warehouseId, productId);
            jdbc.update("UPDATE retail_document_item SET inventory_unit_cost = ? WHERE id = ?", cost, item.get("id"));
            jdbc.update("""
                    INSERT INTO inventory_transaction (id, company_id, warehouse_id, product_id, biz_type, biz_id, quantity_delta, balance_after, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, ApplicationIds.next(), cid, warehouseId, productId, returning ? "RETAIL_RETURN" : "RETAIL_SALE", documentId, delta, next, AuthContext.userId());
        }
        jdbc.update("UPDATE retail_document SET warehouse_id = ?, stock_processed_at = CURRENT_TIMESTAMP WHERE id = ? AND company_id = ?", warehouseId, documentId, cid);
        audit.log(cid, "RETAIL_DOCUMENT", documentId, "STOCK", returning ? "零售退货入库" : "零售销售出库");
    }

    private List<NormalizedItem> normalizeItems(long cid, long customerId, String type, DocumentRequest request, Long selfId) {
        if (request.items() == null || request.items().isEmpty() || request.items().size() > 100)
            throw new BusinessException("请录入 1 至 100 条明细");
        boolean returning = "RETURN_ORDER".equals(type);
        if (returning) required(request.remark(), 500, "退货原因");
        List<Map<String, Object>> originals = returning ? items(number(requireOriginal(cid, customerId, request.originalDocumentId()).get("id"))) : List.of();
        List<NormalizedItem> result = new ArrayList<>(); Set<Long> seen = new HashSet<>();
        for (var item : request.items()) {
            if (item == null) throw new BusinessException("商品明细不能为空");
            BigDecimal quantity = validDecimal(item.quantity(), 4, true, "数量");
            String name, spec, unit, lineType; BigDecimal price; Long originalId = null;
            Map<String, Object> returnSource = null;
            if (returning) {
                originalId = item.originalItemId();
                if (originalId == null || !seen.add(originalId)) throw new BusinessException("请选择原销售商品，且不要重复选择");
                final Long selected = originalId;
                var source = originals.stream().filter(i -> number(i.get("id")) == selected).findFirst()
                        .orElseThrow(() -> new BusinessException("退货商品不属于原销售单"));
                ensureReturnQuantity(source, quantity, selfId);
                returnSource = source;
                name = String.valueOf(source.get("productName")); spec = String.valueOf(source.get("specification"));
                unit = String.valueOf(source.get("baseUnit")); price = decimal(source.get("unitPrice")); lineType = String.valueOf(source.get("lineType"));
            } else {
                name = required(item.productName(), 128, "商品名称"); spec = text(item.specification(), 256, "规格");
                lineType = item.lineType() == null ? "PRODUCT" : item.lineType();
                if (!List.of("PRODUCT", "FEE").contains(lineType)) throw new BusinessException("明细类型不正确");
                unit = "FEE".equals(lineType) ? "项" : required(item.baseUnit(), 32, "单位");
                price = validDecimal(item.unitPrice(), 6, false, "单价");
                if ("FEE".equals(lineType) && quantity.compareTo(BigDecimal.ONE) != 0) throw new BusinessException("费用数量必须为 1");
            }
            BigDecimal amount = returning ? refundAmount(returnSource, quantity, selfId)
                    : quantity.multiply(price).setScale(2, RoundingMode.HALF_UP);
            checkAmount(amount);
            result.add(new NormalizedItem(originalId, lineType, name, spec, unit, quantity, price, amount, text(item.remark(), 500, "明细备注")));
        }
        checkAmount(total(result)); return result;
    }
    private Map<String, Object> requireOriginal(long cid, long customerId, Long id) {
        if (id == null) throw new BusinessException("请选择原销售单");
        var original = requireDocument(cid, id, true);
        if (number(original.get("customer_id")) != customerId || !"SALES_ORDER".equals(original.get("document_type"))
                || !"CONFIRMED".equals(original.get("status"))) throw new BusinessException("请选择该客户已生效的销售单");
        return original;
    }
    private void ensureReturnQuantity(Map<String, Object> original, BigDecimal quantity, Long selfId) {
        if (quantity.compareTo(decimal(original.get("quantity")).subtract(returnedQuantity(number(original.get("id")), selfId))) > 0)
            throw new BusinessException(String.valueOf(original.get("productName")) + "退货数量超过剩余可退数量");
    }
    private BigDecimal returnedQuantity(long originalItemId, Long selfId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(i.quantity), 0) FROM retail_document_item i JOIN retail_document d ON d.id = i.document_id
                WHERE i.original_item_id = ? AND d.status = 'CONFIRMED' AND d.document_type = 'RETURN_ORDER' AND d.id <> ?
                """, BigDecimal.class, originalItemId, selfId == null ? -1L : selfId);
    }
    private BigDecimal returnedAmount(long originalItemId, Long selfId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(i.amount), 0) FROM retail_document_item i JOIN retail_document d ON d.id = i.document_id
                WHERE i.original_item_id = ? AND d.status = 'CONFIRMED' AND d.document_type = 'RETURN_ORDER' AND d.id <> ?
                """, BigDecimal.class, originalItemId, selfId == null ? -1L : selfId);
    }
    private BigDecimal refundAmount(Map<String, Object> original, BigDecimal quantity, Long selfId) {
        long id = number(original.get("id"));
        return returnedQuantity(id, selfId).add(quantity).multiply(decimal(original.get("unitPrice")))
                .setScale(2, RoundingMode.HALF_UP).subtract(returnedAmount(id, selfId));
    }
    private void insertItems(long id, List<NormalizedItem> items) {
        int line = 1;
        for (var item : items) jdbc.update("""
                INSERT INTO retail_document_item (id, document_id, line_no, line_type, original_item_id, product_name,
                    specification, base_unit, quantity, unit_price, amount, remark) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, ApplicationIds.next(), id, line++, item.lineType(), item.originalId(), item.name(), item.spec(), item.unit(), item.quantity(), item.price(), item.amount(), item.remark());
    }
    private List<Map<String, Object>> items(Long id) {
        var rows = jdbc.queryForList("""
                SELECT id, line_type AS lineType, original_item_id AS originalItemId, product_name AS productName,
                    specification, base_unit AS baseUnit, quantity, unit_price AS unitPrice, amount, remark
                FROM retail_document_item WHERE document_id = ? ORDER BY line_no
                """, id);
        for (var row : rows) {
            row.put("id", String.valueOf(row.get("id")));
            if (row.get("originalItemId") != null) row.put("originalItemId", String.valueOf(row.get("originalItemId")));
            for (String field : List.of("quantity", "unitPrice", "amount")) row.put(field, decimal(row.get(field)).toPlainString());
        }
        return rows;
    }
    private Map<String, Object> requireCustomer(long cid, Long id, boolean lock) {
        var rows = jdbc.queryForList("SELECT * FROM retail_customer WHERE company_id = ? AND id = ?" + (lock ? " FOR UPDATE" : ""), cid, id);
        if (rows.isEmpty()) throw new BusinessException("零售客户不存在或无权访问"); return rows.get(0);
    }
    private Map<String, Object> requireDocument(long cid, Long id, boolean lock) {
        var rows = jdbc.queryForList("SELECT * FROM retail_document WHERE company_id = ? AND id = ?" + (lock ? " FOR UPDATE" : ""), cid, id);
        if (rows.isEmpty()) throw new BusinessException("零售单据不存在或无权访问"); return rows.get(0);
    }
    private Map<String, Object> customerView(Map<String, Object> row) {
        var view = new LinkedHashMap<String, Object>();
        view.put("id", String.valueOf(row.get("id"))); view.put("customerType", row.get("customer_type"));
        for (String field : List.of("name", "contact", "phone", "address", "remark")) view.put(field, row.get(field));
        view.put("invoiceTitle", row.get("invoice_title")); view.put("taxNo", row.get("tax_no"));
        BigDecimal sales = decimal(row.get("sales_amount")), returned = decimal(row.get("return_amount"));
        view.put("salesAmount", sales); view.put("returnAmount", returned); view.put("netSalesAmount", sales.subtract(returned));
        view.put("salesOrderCount", row.getOrDefault("sales_count", 0)); return view;
    }
    private Map<String, Object> documentView(Map<String, Object> row) {
        var view = new LinkedHashMap<String, Object>();
        view.put("id", String.valueOf(row.get("id"))); view.put("customerId", String.valueOf(row.get("customer_id")));
        view.put("documentNo", row.get("document_no")); view.put("documentType", row.get("document_type")); view.put("status", row.get("status"));
        view.put("orderDate", String.valueOf(row.get("order_date"))); view.put("amount", row.get("amount"));
        view.put("originalDocumentId", row.get("original_document_id") == null ? null : String.valueOf(row.get("original_document_id")));
        view.put("stockProcessed", row.get("stock_processed_at") != null); view.put("warehouseId", row.get("warehouse_id") == null ? null : String.valueOf(row.get("warehouse_id")));
        return view;
    }
    private long readCompany() {
        long cid = AuthContext.requireCompanyId(); access.requireAnyPermission(cid, "contract_view", "order_create", "reconciliation", "invoice_view"); return cid;
    }
    private long writeCompany() {
        long cid = AuthContext.requireCompanyId(); access.requirePermission(cid, "order_create"); return cid;
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); } catch (Exception e) { throw new BusinessException("单据信息保存失败"); }
    }
    private Map<String, Object> decode(String value) {
        try { return json.readValue(value, new TypeReference<LinkedHashMap<String, Object>>() {}); }
        catch (Exception e) { throw new BusinessException("单据信息读取失败"); }
    }
    private String text(String value, int limit, String label) {
        String result = value == null ? "" : value.trim(); if (result.length() > limit) throw new BusinessException(label + "过长"); return result;
    }
    private String required(String value, int limit, String label) {
        String result = text(value, limit, label); if (result.isEmpty()) throw new BusinessException("请填写" + label); return result;
    }
    private String documentType(String type) {
        if (!List.of("SALES_ORDER", "RETURN_ORDER").contains(type == null ? "" : type)) throw new BusinessException("单据类型不正确"); return type;
    }
    private LocalDate date(String value) {
        try { return LocalDate.parse(value); } catch (Exception e) { throw new BusinessException("请填写正确的单据日期"); }
    }
    private BigDecimal validDecimal(BigDecimal value, int scale, boolean positive, String label) {
        if (value == null || value.scale() > scale || (positive ? value.signum() <= 0 : value.signum() < 0)
                || value.compareTo(BigDecimal.TEN.pow(18 - scale)) >= 0)
            throw new BusinessException(label + (positive ? "须大于零" : "不能为负") + "，最多 " + scale + " 位小数"); return value;
    }
    private void checkAmount(BigDecimal amount) {
        if (amount.signum() < 0) throw new BusinessException("金额不能为负数，请核对原单退货金额");
        if (amount.compareTo(new BigDecimal("9999999999999999.99")) > 0) throw new BusinessException("单据金额过大");
    }
    private static BigDecimal decimal(Object value) { return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value)); }
    private static long number(Object value) { return Long.parseLong(String.valueOf(value)); }
    private static BigDecimal total(List<NormalizedItem> items) { return items.stream().map(NormalizedItem::amount).reduce(BigDecimal.ZERO, BigDecimal::add); }
    private static String label(String type) { return "RETURN_ORDER".equals(type) ? "退货单" : "销售单"; }
    private record NormalizedItem(Long originalId, String lineType, String name, String spec, String unit,
                                  BigDecimal quantity, BigDecimal price, BigDecimal amount, String remark) {}
}
