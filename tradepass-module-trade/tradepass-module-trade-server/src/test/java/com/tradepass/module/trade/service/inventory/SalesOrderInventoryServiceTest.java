package com.tradepass.module.trade.service.inventory;

import com.tradepass.module.trade.service.approval.ApprovalService;

import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.module.identity.api.user.UserIdentityOperations;
import com.tradepass.framework.audit.core.AuditLogService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.support.TestIds;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.module.trade.dal.dataobject.document.BusinessDocumentDO;
import com.tradepass.module.contract.api.contract.dto.TradeContractRespDTO;
import com.tradepass.module.trade.dal.mysql.document.BusinessDocumentMapper;
import com.tradepass.module.contract.api.contract.ContractReader;
import com.tradepass.module.contract.api.contract.ContractReader.*;
import com.tradepass.support.MybatisTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SalesOrderInventoryServiceTest {
    private static final byte[] SIGNATURE = new byte[]{
            (byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a
    };

    private JdbcTemplate jdbc;
    private BusinessDocumentMapper documentMapper;
    private ContractReader contractMapper;
    private AccessControlOperations accessControlService;
    private SalesOrderSignatureService signatureService;
    private UserIdentityOperations userIdentityService;
    private ApprovalService approvalService;
    private SalesOrderInventoryService service;
    private BusinessDocumentDO document;
    private TradeContractRespDTO contract;
    private Map<String, Object> item;

    @BeforeEach
    void setUp() {
        TestIds.use(60L);
        MybatisTestSupport.initialize(BusinessDocumentDO.class, TradeContractRespDTO.class);
        jdbc = mock(JdbcTemplate.class);
        documentMapper = mock(BusinessDocumentMapper.class);
        contractMapper = mock(ContractReader.class);
        accessControlService = mock(AccessControlOperations.class);
        signatureService = mock(SalesOrderSignatureService.class);
        userIdentityService = mock(UserIdentityOperations.class);
        approvalService = mock(ApprovalService.class);
        when(accessControlService.hasPermission(4L, "sales_order_receive")).thenReturn(true);
        when(accessControlService.hasPermission(4L, "inventory_receive")).thenReturn(true);
        when(userIdentityService.requireCurrentVerifiedName(4L)).thenReturn("张采购");
        service = new SalesOrderInventoryServiceImpl(jdbc, documentMapper, contractMapper,
                accessControlService, mock(AuditLogService.class), new ObjectMapper(),
                null, signatureService, userIdentityService);
        service.setApprovalService(approvalService);

        document = new BusinessDocumentDO();
        document.setId(31L);
        document.setCompanyId(3L);
        document.setRecipientCompanyId(4L);
        document.setContractId(12L);
        document.setDocumentType("SALES_ORDER");
        document.setDocumentNo("XS-31");
        document.setTemplateName("标准销售单");
        document.setSourceType("CONTRACT_DEFAULT");
        document.setStatus("ISSUED");
        document.setContent("""
                {"title":"销售单","columns":["序号","品名","规格","单位","数量","单价","金额","备注"],
                 "rows":[["1","商品A","A-1","件","2","3.5","7",""]]}
                """);
        when(documentMapper.selectById(31L)).thenReturn(document);
        when(documentMapper.selectByIdForUpdate(31L)).thenReturn(document);

        contract = new TradeContractRespDTO();
        contract.setId(12L);
        contract.setCompanyId(3L);
        contract.setCounterpartyCompanyId(4L);
        when(contractMapper.selectById(12L)).thenReturn(contract);

        item = Map.of(
                "id", 41L,
                "lineNo", 1,
                "productName", "商品A",
                "specification", "A-1",
                "baseUnit", "件",
                "quantity", new BigDecimal("2.0000"),
                "unitPrice", new BigDecimal("3.500000"),
                "amount", new BigDecimal("7.00"),
                "remark", ""
        );
        AuthContext.set(8L, 4L);
    }

    @AfterEach
    void clearContext() {
        AuthContext.clear();
        TestIds.reset();
    }

    @Test
    void manualInboundRejectsInvalidQuantityAndForeignWarehouse() {
        assertThatThrownBy(() -> service.manualInbound(9L, "manual-request-123", "商品", "", "件",
                BigDecimal.ZERO, BigDecimal.ONE, "")) .isInstanceOf(BusinessException.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(9L), eq(4L))).thenReturn(0L);
        assertThatThrownBy(() -> service.manualInbound(9L, "manual-request-123", "商品", "", "件",
                BigDecimal.ONE, BigDecimal.ONE, "")).hasMessage("仓库不存在");
    }

    @Test
    void manualInboundRetryDoesNotIncreaseStockTwice() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(9L), eq(4L))).thenReturn(1L);
        doReturn(List.of(123L)).when(jdbc).query(anyString(), any(RowMapper.class), eq(4L), eq("manual-request-123"));
        assertThat(service.manualInbound(9L, "manual-request-123", "商品", "", "件",
                BigDecimal.ONE, BigDecimal.ONE, "")).containsEntry("id", 123L);
        verify(accessControlService).requirePermission(4L, "inventory_receive");
        org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.never()).update(anyString(), any(Object[].class));
    }

    @Test
    void manualInboundWritesBalanceAndAuditMovement() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(9L), eq(4L))).thenReturn(1L);
        doReturn(List.of(77L)).when(jdbc).query(argThat(sql -> sql.contains("FROM inventory_product")),
                any(RowMapper.class), eq(4L), eq("商品"), eq(""), eq("件"));
        when(jdbc.queryForObject(anyString(), eq(BigDecimal.class), eq(4L), eq(9L), eq(77L))).thenReturn(new BigDecimal("2"));
        service.manualInbound(9L, "manual-request-123", "商品", "", "件", new BigDecimal("2"), new BigDecimal("3.5"), "期初库存");
        verify(jdbc).update(argThat(sql -> sql.contains("INSERT INTO inventory_manual_entry")),
                anyLong(), eq(4L), eq("manual-request-123"), eq(9L), eq(77L), eq(new BigDecimal("2")),
                eq(new BigDecimal("3.5")), eq(new BigDecimal("7.00")), eq("期初库存"), eq(8L));
        verify(jdbc).update(argThat(sql -> sql.contains("MANUAL_INBOUND")), anyLong(), eq(4L), eq(9L), eq(77L),
                anyLong(), eq(new BigDecimal("2")), eq(new BigDecimal("2")), eq(8L));
    }

    @Test
    void snapshotsStructuredItemsAndValidatesProductRows() {
        service.saveDocumentItems(document);
        verify(jdbc, atLeastOnce()).update(anyString(), any(Object[].class));

        BusinessDocumentDO calculated = copyDocument();
        calculated.setContent("""
                {"columns":["产品名称","数量","单价","备注"],
                 "rows":[["商品B","3","2.25","自动计算"],["","0","0",""]]}
                """);
        service.saveDocumentItems(calculated);

        BusinessDocumentDO invalid = copyDocument();
        invalid.setContent("{" +
                "\"columns\":[\"品名\",\"数量\"],\"rows\":[[\"\",\"2\"]]}" );
        assertThatThrownBy(() -> service.saveDocumentItems(invalid))
                .isInstanceOf(BusinessException.class)
                .hasMessage("销售单商品名称不能为空");

        invalid.setContent("{\"columns\":[\"品名\",\"数量\"],\"rows\":[[\"商品\",\"0\"]]}");
        assertThatThrownBy(() -> service.saveDocumentItems(invalid))
                .isInstanceOf(BusinessException.class)
                .hasMessage("销售单商品数量必须大于 0");

        invalid.setContent("bad-json");
        assertThatThrownBy(() -> service.saveDocumentItems(invalid))
                .isInstanceOf(BusinessException.class)
                .hasMessage("销售单商品明细格式不正确");

        BusinessDocumentDO noRecipient = copyDocument();
        noRecipient.setRecipientCompanyId(null);
        service.saveDocumentItems(noRecipient);
    }

    @Test
    void snapshotsFeeRowsWithoutTreatingThemAsZeroQuantityProducts() {
        document.setContent("""
                {"columns":["序号","品名","规格","单位","数量","单价","金额","备注"],
                 "rowTypes":["PRODUCT","FEE"],
                 "rows":[
                   ["1","商品A","A-1","件","2","3.5","7",""],
                   ["2","运费","","项","0","15","15","送货上门"]
                 ]}
                """);

        service.saveDocumentItems(document);

        verify(jdbc).update(argThat(sql -> sql.contains("business_document_item")),
                anyLong(), eq(31L), eq(3L), eq(4L), eq(2), eq("FEE"), eq("运费"), eq(""), eq("项"),
                eq(new BigDecimal("1.0000")), eq(new BigDecimal("15.000000")),
                eq(new BigDecimal("15.00")), eq("送货上门"));
    }

    @Test
    void exposesSharedSalesOrderDetailAndStateFlags() {
        doReturn(List.of(item)).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));

        Map<String, Object> detail = service.documentDetail(31L);
        assertThat(detail).containsEntry("statusText", "待我方确认")
                .containsEntry("canReceive", true)
                .containsEntry("canInbound", true);
        assertThat((List<?>) detail.get("items")).hasSize(1);

        document.setStatus("ACKNOWLEDGED");
        detail = service.documentDetail(31L);
        assertThat(detail).containsEntry("statusText", "已通过待入库")
                .containsEntry("canReceive", false)
                .containsEntry("canInbound", true);

        document.setStatus("INBOUNDED");
        assertThat(service.documentDetail(31L)).containsEntry("statusText", "已通过并入库");

        document.setContent("bad-json");
        assertThat(service.documentDetail(31L).get("content")).isEqualTo(Map.of());
    }

    @Test
    void rejectsMissingNonSalesAndForeignDocuments() {
        when(documentMapper.selectById(99L)).thenReturn(null);
        assertThatThrownBy(() -> service.documentDetail(99L))
                .isInstanceOf(BusinessException.class).hasMessage("销售单不存在");

        BusinessDocumentDO other = copyDocument();
        other.setDocumentType("OTHER");
        when(documentMapper.selectById(98L)).thenReturn(other);
        assertThatThrownBy(() -> service.documentDetail(98L))
                .isInstanceOf(BusinessException.class).hasMessage("销售单不存在");

        AuthContext.set(9L, 6L);
        assertThatThrownBy(() -> service.documentDetail(31L))
                .isInstanceOf(BusinessException.class).hasMessage("销售单不存在");
    }

    @Test
    void draftIsVisibleToSupplierOnlyAndCannotBeReceived() {
        document.setStatus("DRAFT");
        contract.setStatus("PENDING");

        assertThatThrownBy(() -> service.documentDetail(31L))
                .isInstanceOf(BusinessException.class).hasMessage("销售单不存在");
        assertThatThrownBy(() -> service.receive(31L, "RECEIVE_ONLY", null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("销售单尚未发布，不能接收或入库");

        AuthContext.set(9L, 3L);
        doReturn(List.of(item)).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
        assertThat(service.documentDetail(31L))
                .containsEntry("statusText", "草稿")
                .containsEntry("canEditDraft", true)
                .containsEntry("canPublish", false);
    }

    @Test
    void listsInventoryAndCreatesWarehouse() {
        Map<String, Object> warehouse = Map.of("id", 55L, "name", "一号仓");
        Map<String, Object> balance = Map.of("id", 66L, "productName", "商品A");
        doReturn(List.of(warehouse), List.of(balance), List.of(warehouse))
                .when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
        TestIds.use(55L);

        assertThat(service.listWarehouses()).containsExactly(warehouse);
        Map<String, Object> overview = service.inventoryOverview();
        assertThat(overview).containsEntry("warehouseCount", 1L).containsEntry("productCount", 1);
        assertThat(service.createWarehouse(" 一号仓 ", " 河北 ")).containsEntry("id", 55L);

        assertThatThrownBy(() -> service.createWarehouse("", ""))
                .isInstanceOf(BusinessException.class).hasMessageContaining("仓库名称不能为空");
        assertThatThrownBy(() -> service.createWarehouse("仓", "x".repeat(257)))
                .isInstanceOf(BusinessException.class).hasMessage("仓库地址不能超过 256 字");
    }

    @Test
    void reportsDuplicateWarehouseName() {
        doThrow(new DuplicateKeyException("duplicate"))
                .when(jdbc).update(anyString(), any(Object[].class));
        assertThatThrownBy(() -> service.createWarehouse("重复仓", ""))
                .isInstanceOf(BusinessException.class).hasMessage("仓库名称已存在");
    }

    @Test
    void receivesSalesOrderWithoutChangingInventory() {
        document.setStatus("ISSUED");
        doReturn(null).when(jdbc).query(anyString(), any(ResultSetExtractor.class), any(Object[].class));
        doReturn(List.of(item)).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
        TestIds.use(60L);

        Map<String, Object> result = service.receive(
                31L, "receive_only", null, null, "签名.png", SIGNATURE);
        assertThat(result).containsEntry("status", "ACKNOWLEDGED")
                .containsEntry("canInbound", true);
        assertThat(document.getAcknowledgedBy()).isEqualTo(8L);
        verify(signatureService).save(4L, 31L, 60L, "用户8", "签名.png", SIGNATURE);
        verify(approvalService).recordResult(3L, 4L, "SALES_ORDER", 31L, 12L,
                "APPROVED", "销售单已确认", "对方已确认销售单 XS-31", null);

        assertThatThrownBy(() -> service.receive(31L, "bad", null))
                .isInstanceOf(BusinessException.class).hasMessage("接收方式不正确");
    }

    @Test
    void initialConfirmationRequiresHandwrittenSignature() {
        document.setStatus("ISSUED");

        assertThatThrownBy(() -> service.receive(31L, "RECEIVE_ONLY", null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("请先完成手写签名");
    }

    @Test
    void recipientCanRejectPendingSalesOrderWithReason() {
        document.setStatus("ISSUED");
        doReturn(List.of(item)).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));

        Map<String, Object> result = service.receive(31L, "REJECT", null, "金额需要核对");

        assertThat(result).containsEntry("status", "REJECTED")
                .containsEntry("statusText", "已驳回")
                .containsEntry("rejectedReason", "金额需要核对");
        verify(approvalService).recordResult(3L, 4L, "SALES_ORDER", 31L, 12L,
                "REJECTED", "销售单已被驳回", "对方已驳回销售单 XS-31", "金额需要核对");
        assertThatThrownBy(() -> service.receive(31L, "REJECT", null, ""))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("当前状态不能驳回");
    }

    @Test
    void receivesAndInboundsEverySalesItemExactlyOnce() {
        document.setStatus("ISSUED");
        doReturn((Object) null).when(jdbc)
                .query(anyString(), any(ResultSetExtractor.class), any(Object[].class));
        doReturn(List.of(item), List.of(), List.of(item)).when(jdbc)
                .query(anyString(), any(RowMapper.class), any(Object[].class));
        TestIds.use(60L, 70L, 80L);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
        when(jdbc.queryForObject(anyString(), eq(BigDecimal.class), any(Object[].class)))
                .thenReturn(new BigDecimal("2.0000"));

        Map<String, Object> result = service.receive(
                31L, "INBOUND", 55L, null, "签名.png", SIGNATURE);
        assertThat(result).containsEntry("status", "INBOUNDED")
                .containsEntry("canInbound", false);
        verify(accessControlService).requirePermission(4L, "inventory_receive");
        verify(jdbc, atLeastOnce()).update(
                argThat(sql -> sql.contains("inventory_amount")), any(Object[].class));
    }

    @Test
    void returnInboundDecreasesBuyerAndIncreasesSupplierInventory() throws Exception {
        document.setCompanyId(4L);
        document.setRecipientCompanyId(3L);
        document.setSupplierCompanyId(3L);
        document.setBuyerCompanyId(4L);
        document.setDocumentType("RETURN_ORDER");
        document.setStatus("ISSUED");
        document.setOutboundWarehouseId(44L);
        contract.setStatus("ACTIVE");
        AuthContext.set(18L, 3L);
        when(accessControlService.hasPermission(3L, "inventory_receive")).thenReturn(true);

        doReturn((Object) null).when(jdbc)
                .query(anyString(), any(ResultSetExtractor.class), any(Object[].class));
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            RowMapper<?> mapper = invocation.getArgument(1);
            if (sql.contains("FROM business_document_item")) return List.of(item);
            if (sql.contains("JOIN inventory_balance balance")) {
                ResultSet rs = mock(ResultSet.class);
                when(rs.getLong("product_id")).thenReturn(501L);
                when(rs.getBigDecimal("quantity")).thenReturn(new BigDecimal("10.0000"));
                when(rs.getBigDecimal("unit_price")).thenReturn(new BigDecimal("2.000000"));
                when(rs.getBigDecimal("inventory_amount")).thenReturn(new BigDecimal("20.00"));
                return List.of(mapper.mapRow(rs, 0));
            }
            return List.of();
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenAnswer(invocation -> invocation.<String>getArgument(0)
                        .contains("bilateral_action_request") ? 0L : 1L);
        TestIds.use(60L, 70L, 71L, 80L, 90L, 91L, 92L, 93L);
        when(jdbc.queryForObject(anyString(), eq(BigDecimal.class), any(Object[].class)))
                .thenReturn(new BigDecimal("2.0000"));

        Map<String, Object> result = service.receive(
                31L, "INBOUND", 55L, null, "签名.png", SIGNATURE);

        assertThat(result).containsEntry("status", "INBOUNDED");
        assertThat(document.getInboundWarehouseId()).isEqualTo(55L);
        verify(jdbc).update(argThat(sql -> sql.contains("'RETURN_ORDER_OUTBOUND'")),
                anyLong(), eq(4L), eq(44L), eq(501L), eq(70L),
                eq(new BigDecimal("-2.0000")), eq(new BigDecimal("8.0000")), eq(18L));
        verify(jdbc).update(argThat(sql -> sql.contains("INSERT INTO inventory_inbound")),
                eq(80L), eq(3L), eq(55L), eq(31L), anyString(), eq(18L));
        verify(jdbc).update(argThat(sql -> sql.contains("INSERT INTO inventory_transaction")
                        && !sql.contains("'RETURN_ORDER_OUTBOUND'")),
                anyLong(), eq(3L), eq(55L), eq(90L), eq("RETURN_ORDER_INBOUND"), eq(70L),
                eq(new BigDecimal("2.0000")), eq(new BigDecimal("2.0000")), eq(18L));
    }

    @Test
    void rejectsWrongRecipientAndMissingWarehouse() {
        AuthContext.set(9L, 3L);
        assertThatThrownBy(() -> service.receive(31L, "RECEIVE_ONLY", null))
                .isInstanceOf(BusinessException.class).hasMessage("待接收销售单不存在");

        AuthContext.set(8L, 4L);
        doReturn(null).when(jdbc).query(anyString(), any(ResultSetExtractor.class), any(Object[].class));
        TestIds.use(60L);
        assertThatThrownBy(() -> service.receive(
                31L, "INBOUND", null, null, "签名.png", SIGNATURE))
                .isInstanceOf(BusinessException.class).hasMessage("请选择入库仓库");
    }

    private BusinessDocumentDO copyDocument() {
        BusinessDocumentDO copy = new BusinessDocumentDO();
        copy.setId(document.getId());
        copy.setCompanyId(document.getCompanyId());
        copy.setRecipientCompanyId(document.getRecipientCompanyId());
        copy.setContractId(document.getContractId());
        copy.setDocumentType(document.getDocumentType());
        copy.setDocumentNo(document.getDocumentNo());
        copy.setStatus(document.getStatus());
        copy.setContent(document.getContent());
        return copy;
    }
}
