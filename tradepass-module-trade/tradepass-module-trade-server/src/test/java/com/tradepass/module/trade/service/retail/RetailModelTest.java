package com.tradepass.module.trade.service.retail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradepass.framework.audit.core.AuditLogService;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.module.identity.api.company.CompanyReader;
import com.tradepass.module.identity.api.company.dto.CompanyRespDTO;
import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.module.identity.api.user.UserIdentityOperations;
import com.tradepass.support.TestIds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.tradepass.module.trade.service.retail.RetailDtos.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RetailModelTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final CompanyReader companies = mock(CompanyReader.class);
    private final RetailService service = new RetailService(jdbc, mock(AccessControlOperations.class), companies,
            mock(UserIdentityOperations.class), mock(AuditLogService.class), new ObjectMapper());
    private Map<String, Object> savedDocument;

    @BeforeEach void setUp() {
        AuthContext.set(8L, 4L);
        TestIds.use(60L, 61L, 62L, 63L, 64L);
        var company = new CompanyRespDTO(); company.setId(4L); company.setName("本公司");
        when(companies.selectById(4L)).thenReturn(company);
        var customer = new LinkedHashMap<String, Object>(); customer.put("id", 9L); customer.put("name", "客户");
        when(jdbc.queryForList(contains("SELECT * FROM retail_customer"), eq(4L), eq(9L)))
                .thenReturn(List.of(customer));
        savedDocument = document(60L, "SALES_ORDER", "DRAFT");
        when(jdbc.queryForList(argThat(sql -> sql != null && sql.startsWith("SELECT * FROM retail_document WHERE company_id = ? AND id = ?")),
                eq(4L), eq(60L))).thenAnswer(inv -> List.of(savedDocument));
        when(jdbc.queryForObject(anyString(), eq(BigDecimal.class), anyLong(), anyLong())).thenReturn(BigDecimal.ZERO);
    }

    @AfterEach void clear() { AuthContext.clear(); TestIds.reset(); }

    @Test void retailSaleStoresModelIndependentlyOfSpecificationAndAcceptsOldRequests() {
        var request = new ItemRequest(null, "PRODUCT", "电缆", " YJV ", "3×25", "米", BigDecimal.ONE, BigDecimal.TEN, "");
        service.createDocument(9L, request("SALES_ORDER", null, request));
        verify(jdbc).update(contains("INSERT INTO retail_document_item"), anyLong(), eq(60L), eq(1),
                eq("PRODUCT"), isNull(), eq("电缆"), eq("YJV"), eq("3×25"), eq("米"),
                eq(BigDecimal.ONE), eq(BigDecimal.TEN), eq(new BigDecimal("10.00")), eq(""));
        assertThat(new ItemRequest(null, "PRODUCT", "电缆", "3×25", "米", BigDecimal.ONE, BigDecimal.TEN, "").model())
                .isEmpty();
    }

    @Test void returnAlwaysInheritsTheOriginalModelInsteadOfAcceptingAnOverriddenModel() {
        var original = document(19L, "SALES_ORDER", "CONFIRMED");
        when(jdbc.queryForList(argThat(sql -> sql != null && sql.startsWith("SELECT * FROM retail_document WHERE company_id = ? AND id = ?")),
                eq(4L), eq(19L))).thenReturn(List.of(original));
        when(jdbc.queryForList(argThat(sql -> sql != null && sql.contains("FROM retail_document_item WHERE document_id")), eq(19L)))
                .thenReturn(List.of(item(21L, "YJV")));
        savedDocument = document(60L, "RETURN_ORDER", "DRAFT");
        var forged = new ItemRequest(21L, "PRODUCT", "其他商品", "YJLV", "错误规格", "吨", BigDecimal.ONE, BigDecimal.ZERO, "");
        service.createDocument(9L, request("RETURN_ORDER", 19L, forged));
        verify(jdbc).update(contains("INSERT INTO retail_document_item"), anyLong(), eq(60L), eq(1),
                eq("PRODUCT"), eq(21L), eq("电缆"), eq("YJV"), eq("3×25"), eq("米"),
                eq(BigDecimal.ONE), eq(BigDecimal.TEN), eq(new BigDecimal("10.00")), eq(""));
    }

    @Test void retailStockMatchesTheSelectedModelAndKeepsItInTheGeneratedDocument() throws Exception {
        savedDocument.put("status", "CONFIRMED");
        when(jdbc.queryForList(argThat(sql -> sql != null && sql.contains("FROM retail_document_item WHERE document_id")), eq(60L)))
                .thenAnswer(inv -> List.of(item(61L, "YJV")));
        when(jdbc.queryForList(contains("FROM warehouse"), eq(9L), eq(4L)))
                .thenReturn(List.of(Map.of("id", 9L)));
        when(jdbc.queryForList(contains("FROM inventory_product"), eq(Long.class), eq(4L), eq("电缆"), eq("3×25"), eq("米"), eq("YJV")))
                .thenReturn(List.of(77L));
        when(jdbc.queryForMap(contains("FROM inventory_balance"), eq(4L), eq(9L), eq(77L)))
                .thenReturn(Map.of("quantity", new BigDecimal("5"), "unit_price", BigDecimal.TEN, "inventory_amount", new BigDecimal("50")));

        var result = service.processStock(60L, 9L);
        assertThat((List<?>) result.get("items")).singleElement().satisfies(row ->
                assertThat(((Map<?, ?>) row).get("model")).isEqualTo("YJV"));
        verify(jdbc).update(contains("UPDATE inventory_balance"), eq(new BigDecimal("4")), eq(new BigDecimal("10.000000")),
                eq(new BigDecimal("40.00")), eq(4L), eq(9L), eq(77L));
        var content = new ObjectMapper().readTree(service.pdfDocument(60L).getContent());
        assertThat(content.path("columns").get(2).asText()).isEqualTo("型号");
        assertThat(content.path("rows").get(0).get(2).asText()).isEqualTo("YJV");
        assertThat(content.path("rows").get(0).get(3).asText()).isEqualTo("3×25");
    }

    private DocumentRequest request(String type, Long original, ItemRequest item) {
        return new DocumentRequest("retail-model-test-123", type, original, "2026-10-26", "测试原因", List.of(item));
    }

    private Map<String, Object> document(long id, String type, String status) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", id); result.put("customer_id", 9L); result.put("document_type", type);
        result.put("status", status); result.put("document_no", "LS-" + id); result.put("customer_snapshot", "{\"name\":\"客户\"}");
        return result;
    }

    private Map<String, Object> item(long id, String model) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", id); result.put("lineType", "PRODUCT"); result.put("productName", "电缆");
        result.put("model", model); result.put("specification", "3×25"); result.put("baseUnit", "米");
        result.put("quantity", BigDecimal.ONE); result.put("unitPrice", BigDecimal.TEN); result.put("amount", BigDecimal.TEN); result.put("remark", "");
        return result;
    }
}
