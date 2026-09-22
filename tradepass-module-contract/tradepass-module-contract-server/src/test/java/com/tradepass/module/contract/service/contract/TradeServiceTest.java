package com.tradepass.module.contract.service.contract;

import com.tradepass.module.identity.service.permission.RolePermissionServiceImpl;

import com.tradepass.module.identity.service.permission.AccessControlServiceImpl;

import com.tradepass.framework.common.pojo.TradePassDtos;
import com.tradepass.module.contract.service.archive.ContractArchiveService;
import com.tradepass.module.contract.service.signing.ContractSigningCancellationService;
import com.tradepass.module.identity.dal.dataobject.company.CompanyMemberDO;
import com.tradepass.module.identity.dal.dataobject.permission.RoleDefDO;
import com.tradepass.module.identity.dal.mysql.company.CompanyMemberMapper;
import com.tradepass.module.identity.dal.mysql.permission.RoleDefMapper;

import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.module.identity.api.permission.AccessControlOperationsImpl;
import com.tradepass.module.identity.service.permission.AccessControlService;
import com.tradepass.module.identity.service.permission.RolePermissionService;
import com.tradepass.framework.audit.core.AuditLogService;
import com.tradepass.module.trade.api.approval.ApprovalOperations;
import com.tradepass.module.trade.api.ranking.RankingCacheOperations;
import com.tradepass.module.trade.service.approval.ApprovalService;
import com.tradepass.module.trade.service.ranking.RankingCacheService;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.common.pojo.TradePassDtos.CounterpartyRelation;
import com.tradepass.module.identity.controller.app.counterparty.vo.AddCounterpartyReqVO;
import com.tradepass.module.contract.controller.app.contract.vo.CreateContractReqVO;
import com.tradepass.module.trade.controller.app.order.vo.CreateOrderReqVO;
import com.tradepass.module.contract.api.contract.dto.ContractRespDTO;
import com.tradepass.module.trade.api.order.dto.TradeOrderRespDTO;
import com.tradepass.module.identity.api.company.dto.CompanyRespDTO;
import com.tradepass.module.contract.dal.dataobject.template.ContractTemplateDO;
import com.tradepass.module.identity.dal.dataobject.counterparty.CounterpartyRelationEntityDO;
import com.tradepass.module.contract.dal.dataobject.template.TemplateCategoryDO;
import com.tradepass.module.contract.dal.dataobject.contract.TradeContractDO;
import com.tradepass.module.trade.dal.dataobject.order.TradeOrderDO;
import com.tradepass.module.identity.api.company.CompanyReader;
import com.tradepass.module.identity.api.company.CompanyReader.*;
import com.tradepass.module.contract.dal.mysql.template.ContractTemplateMapper;
import com.tradepass.module.identity.dal.mysql.counterparty.CounterpartyRelationMapper;
import com.tradepass.module.contract.dal.mysql.template.TemplateCategoryMapper;
import com.tradepass.module.contract.dal.mysql.contract.TradeContractMapper;
import com.tradepass.module.trade.dal.mysql.order.TradeOrderMapper;
import com.tradepass.support.MybatisTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class TradeServiceTest {
    private TradeOrderMapper orderMapper;
    private CounterpartyRelationMapper relationMapper;
    private TemplateCategoryMapper categoryMapper;
    private ContractTemplateMapper templateMapper;
    private TradeContractMapper contractMapper;
    private CompanyReader companyMapper;
    private AccessControlOperations accessControl;
    private AuditLogService auditLogService;
    private RankingCacheService rankingCache;
    private ContractArchiveService contractArchiveService;
    private ApprovalService approvalService;
    private ContractSigningCancellationService signingCancellation;
    private LegacyMixedTradeService service;

    @BeforeEach
    void setUp() {
        MybatisTestSupport.initialize(
                ContractTemplateDO.class, TradeContractDO.class, TemplateCategoryDO.class, CompanyRespDTO.class,
                CounterpartyRelationEntityDO.class, TradeOrderDO.class
        );
        orderMapper = mock(TradeOrderMapper.class);
        relationMapper = mock(CounterpartyRelationMapper.class);
        categoryMapper = mock(TemplateCategoryMapper.class);
        templateMapper = mock(ContractTemplateMapper.class);
        contractMapper = mock(TradeContractMapper.class);
        companyMapper = mock(CompanyReader.class);
        accessControl = mock(AccessControlOperations.class, withSettings().extraInterfaces(AccessControlService.class));
        auditLogService = mock(AuditLogService.class);
        rankingCache = mock(RankingCacheService.class, withSettings().extraInterfaces(RankingCacheOperations.class));
        contractArchiveService = mock(ContractArchiveService.class);
        approvalService = mock(ApprovalService.class, withSettings().extraInterfaces(ApprovalOperations.class));
        service = new LegacyMixedTradeService(orderMapper, relationMapper, categoryMapper, templateMapper,
                contractMapper, companyMapper, accessControl, auditLogService,
                rankingCache, contractArchiveService);
        service.setApprovalService((ApprovalOperations) approvalService);
        signingCancellation = mock(ContractSigningCancellationService.class);
        service.setSigningCancellation(signingCancellation);
        when(contractMapper.update(any(Wrapper.class))).thenReturn(1);
        AuthContext.set(7L, 3L);
    }

    @AfterEach
    void clearContext() {
        AuthContext.clear();
    }

    @Test
    void financeReadsContractBasicsWithoutReceivingContractTerms() {
        TradeContractDO contract = new TradeContractDO();
        contract.setId(51L);
        contract.setCompanyId(3L);
        contract.setCounterpartyCompanyId(4L);
        contract.setDirection("SALE");
        contract.setStatus("ACTIVE");
        contract.setInitiatorHidden(false);
        contract.setName("项目购销合同");
        contract.setTerms("仅合同阅读者可见的条款");
        when(contractMapper.selectById(51L)).thenReturn(contract);
        when(accessControl.hasPermission(3L, "reconciliation")).thenReturn(true);

        ContractRespDTO result = service.getContract(51L);

        assertThat(result.name()).isEqualTo("项目购销合同");
        assertThat(result.terms()).isEmpty();
        verify(accessControl).requireAnyPermission(3L, "contract_view", "contract_sign",
                "reconciliation", "invoice_view", "contract_attachment_upload");
        when(accessControl.hasPermission(3L, "contract_view")).thenReturn(true);
        assertThat(service.getContract(51L).terms()).isEqualTo("仅合同阅读者可见的条款");
        contract.setCompanyId(9L);
        assertThatThrownBy(() -> service.getContract(51L)).hasMessage("合同不存在");
    }

    @Test
    void createsAndListsTenantScopedOrders() {
        doAnswer(invocation -> {
            TradeOrderDO order = invocation.getArgument(0);
            order.setId(12L);
            return 1;
        }).when(orderMapper).insert(any(TradeOrderDO.class));
        CreateOrderReqVO request = new CreateOrderReqVO("SALE", "合作方",
                new BigDecimal("88.50"), LocalDate.of(2026, 7, 1));

        TradeOrderRespDTO created = service.createOrder(request);

        assertThat(created.id()).isEqualTo("12");
        assertThat(created.status()).isEqualTo("CONFIRMED");
        assertThat(created.amount()).isEqualByComparingTo("88.50");
        verify(rankingCache).evict(3L, "SALE");

        TradeOrderDO order = new TradeOrderDO();
        order.setId(12L);
        order.setDirection("SALE");
        order.setCounterpartyName("合作方");
        order.setAmount(new BigDecimal("88.50"));
        order.setOrderDate(LocalDate.of(2026, 7, 1));
        order.setStatus("CONFIRMED");
        when(orderMapper.selectList(any(Wrapper.class))).thenReturn(List.of(order));
        assertThat(service.listOrders(" 合作方 ")).extracting(TradeOrderRespDTO::id).containsExactly("12");
    }

    @Test
    void mapsBuyerAndSupplierCounterpartyViews() {
        when(accessControl.resolveCompanyId("3")).thenReturn(3L);
        when(relationMapper.selectBuyerCounterparties(3L)).thenReturn(List.of(
                Map.of("id", 5L, "counterpartyCompanyId", 9L, "counterpartyName", "供应方", "relationType", "SUPPLIER", "status", "ACTIVE"),
                Map.of("id", 7L, "counterpartyName", "历史名称关系", "relationType", "SUPPLIER", "status", "ACTIVE")
        ));

        List<CounterpartyRelation> buyerView = service.listCounterparties("3", "buyer");
        assertThat(buyerView).hasSize(1);
        assertThat(buyerView.get(0).counterpartyName()).isEqualTo("供应方");

        when(relationMapper.selectSupplierCounterparties(3L)).thenReturn(List.of(
                Map.of("id", 6L, "counterpartyCompanyId", 10L, "counterpartyName", "采购方", "relationType", "CUSTOMER", "status", "ACTIVE")
        ));
        List<CounterpartyRelation> supplierView = service.listCounterparties("3", "supplier");
        assertThat(supplierView.get(0).counterpartyName()).isEqualTo("采购方");
        assertThat(supplierView.get(0).relationType()).isEqualTo("CUSTOMER");
    }

    @Test
    void administratorReadsCompanyBindingsAcrossMembersButStillHonorsPermissionsAndTenant() {
        var memberMapper = mock(com.tradepass.module.identity.dal.mysql.company.CompanyMemberMapper.class);
        var roleMapper = mock(com.tradepass.module.identity.dal.mysql.permission.RoleDefMapper.class);
        var acl = new AccessControlServiceImpl(memberMapper, relationMapper, roleMapper,
                new RolePermissionServiceImpl(), new com.fasterxml.jackson.databind.ObjectMapper());
        service = new LegacyMixedTradeService(orderMapper, relationMapper, categoryMapper, templateMapper,
                contractMapper, companyMapper, new AccessControlOperationsImpl(acl), acl,
                auditLogService, rankingCache, contractArchiveService);
        var member = new com.tradepass.module.identity.dal.dataobject.company.CompanyMemberDO();
        member.setRoleCode("ADMIN");
        member.setStatus("ACTIVE");
        when(memberMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        when(memberMapper.selectOne(any(Wrapper.class))).thenReturn(member);
        var role = new com.tradepass.module.identity.dal.dataobject.permission.RoleDefDO();
        role.setCode("ADMIN");
        role.setPermissions("[\"member_manage\",\"counterparty_view\"]");
        when(roleMapper.selectOne(any(Wrapper.class))).thenReturn(role);
        when(relationMapper.selectSupplierCounterparties(3L)).thenReturn(List.of(
                Map.of("id", 5L, "counterpartyCompanyId", 9L, "counterpartyName", "B公司", "relationType", "CUSTOMER", "status", "ACTIVE")));
        when(relationMapper.selectBuyerCounterparties(3L)).thenReturn(List.of(
                Map.of("id", 6L, "counterpartyCompanyId", 10L, "counterpartyName", "C公司", "relationType", "SUPPLIER", "status", "ACTIVE")));
        for (long userId : List.of(7L, 8L)) {
            AuthContext.set(userId, 3L);
            assertThat(service.listCounterparties("3", "supplier")).extracting(CounterpartyRelation::counterpartyName).containsExactly("B公司");
            assertThat(service.listCounterparties("3", "buyer")).extracting(CounterpartyRelation::counterpartyName).containsExactly("C公司");
        }
        role.setPermissions("[\"member_manage\"]");
        assertThatThrownBy(() -> service.listCounterparties("3", "supplier")).hasMessage("无权执行该操作");
        assertThatThrownBy(() -> service.listCounterparties("3", "buyer")).hasMessage("无权执行该操作");
        when(memberMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        assertThatThrownBy(() -> service.listCounterparties("9", "buyer")).hasMessage("你不是该企业的有效成员");
        verify(relationMapper, org.mockito.Mockito.never()).selectBuyerCounterparties(9L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void pagesTenantOrdersContractsAndTemplates() {
        TradeOrderDO order = new TradeOrderDO();
        order.setId(12L);
        order.setDirection("SALE");
        order.setCounterpartyName("客户");
        order.setAmount(new BigDecimal("88.50"));
        order.setOrderDate(LocalDate.of(2026, 7, 1));
        order.setStatus("CONFIRMED");
        when(orderMapper.selectCount(any(Wrapper.class))).thenReturn(25L);
        when(orderMapper.selectList(any(Wrapper.class))).thenReturn(List.of(order));
        assertThat(service.pageOrders("客户", "SALE", 2, 10)).satisfies(page -> {
            assertThat(page.total()).isEqualTo(25);
            assertThat(page.hasMore()).isTrue();
            assertThat(page.items()).extracting(TradeOrderRespDTO::id).containsExactly("12");
        });
        when(orderMapper.selectMonthlyOrderSummary(3L, "客户", "SALE"))
                .thenReturn(List.of(Map.of("period", "2026-07", "amount", new BigDecimal("88.50"))));
        assertThat(service.monthlyOrderSummary(" 客户 ", " SALE "))
                .extracting(item -> item.get("period")).containsExactly("2026-07");
        assertThatThrownBy(() -> service.monthlyOrderSummary("", "SALE"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("合作方和交易方向不能为空");

        TradeContractDO contract = new TradeContractDO();
        contract.setId(20L);
        contract.setCompanyId(3L);
        contract.setCounterpartyName("客户");
        contract.setName("采购合同");
        contract.setAmount(BigDecimal.TEN);
        contract.setStatus("PENDING");
        contract.setInitiatedBy(7L);
        when(contractMapper.countPartyContracts(3L, null, "PENDING")).thenReturn(1L);
        when(contractMapper.selectPartyContracts(3L, null, "PENDING", 20, 0L)).thenReturn(List.of(contract));
        assertThat(service.pageContracts(null, "PENDING", 1, 20).items())
                .extracting(ContractRespDTO::id).containsExactly("20");

        when(templateMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        when(templateMapper.selectTemplatePageViews(3L, "采购", "通用", 20, 0L))
                .thenReturn(List.of(Map.of("id", 2L, "name", "采购合同")));
        assertThat(service.pageTemplates(" 采购 ", "通用", 1, 20).items())
                .extracting(item -> item.get("name")).containsExactly("采购合同");
    }

    @Test
    void requiresInvitationWhenAddingCounterparty() {
        assertThatThrownBy(() -> service.addCounterparty(new AddCounterpartyReqVO("新供应商")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("合作企业邀请");
        verify(accessControl).requireLegal(3L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void validatesAndCreatesTemplateCategory() {
        assertThatThrownBy(() -> service.addCategory(Map.of("name", "  ")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("分类名不能为空");

        when(categoryMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(categoryMapper.selectMaps(any(Wrapper.class))).thenReturn(List.of(Map.of("id", 4L, "name", "采购")));
        Map<String, Object> result = service.addCategory(Map.of("name", " 采购 "));

        assertThat(result).containsEntry("name", "采购");
        verify(categoryMapper).insert(any(com.tradepass.module.contract.dal.dataobject.template.TemplateCategoryDO.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void validatesCreatesAndUpdatesContractTemplates() {
        assertThatThrownBy(() -> service.createTemplate(Map.of("name", " ")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("模板名称不能为空");

        doAnswer(invocation -> {
            ContractTemplateDO template = invocation.getArgument(0);
            template.setId(9L);
            return 1;
        }).when(templateMapper).insert(any(ContractTemplateDO.class));
        Map<String, Object> view = Map.of("id", 9L, "name", "标准合同", "category", "采购");
        when(templateMapper.selectMaps(any(Wrapper.class))).thenReturn(List.of(view));

        assertThat(service.createTemplate(Map.of("name", "标准合同", "category", "采购", "content", "{}")))
                .containsEntry("id", 9L);
        assertThat(service.updateTemplate(9L, Map.of("name", "新版合同", "content", "{}")))
                .containsEntry("id", 9L);
        verify(templateMapper).update(any(Wrapper.class));
    }

    @Test
    void rejectsMissingTemplateAndContract() {
        when(templateMapper.selectTemplateView(99L, 3L)).thenReturn(Map.of());
        assertThatThrownBy(() -> service.getTemplate(99L))
                .isInstanceOf(BusinessException.class).hasMessage("模板不存在");

        when(contractMapper.selectById(99L)).thenReturn(null);
        assertThatThrownBy(() -> service.getContract(99L))
                .isInstanceOf(BusinessException.class).hasMessage("合同不存在");
    }

    @Test
    void createsContractAndProtectsStatusTransitions() {
        doAnswer(invocation -> {
            TradeContractDO contract = invocation.getArgument(0);
            contract.setId(20L);
            return 1;
        }).when(contractMapper).insert(any(TradeContractDO.class));
        CompanyRespDTO initiator = new CompanyRespDTO();
        initiator.setId(3L);
        initiator.setName("当前企业");
        CompanyRespDTO counterparty = new CompanyRespDTO();
        counterparty.setId(9L);
        counterparty.setName("对方企业");
        when(companyMapper.selectById(3L)).thenReturn(initiator);
        when(companyMapper.selectById(9L)).thenReturn(counterparty);
        when(relationMapper.countActiveBetween(3L, 9L)).thenReturn(1L);
        CreateContractReqVO request = new CreateContractReqVO("用户输入名称", "年度合同", "模板",
                new BigDecimal("1000"), "2026-01-01", "2026-12-31", "{}",
                9L, "SALE", null, "request-20");

        ContractRespDTO created = service.createContract(request);
        assertThat(created.id()).isEqualTo("20");
        assertThat(created.startDate()).isEqualTo("2026-01-01");
        assertThat(created.status()).isEqualTo("PENDING");
        assertThat(created.initiatedBy()).isEqualTo("7");
        assertThat(created.counterpartyName()).isEqualTo("对方企业");
        assertThat(created.viewerDirection()).isEqualTo("SALE");
        assertThat(created.terms()).contains("\"title\":\"年度合同\"");

        TradeContractDO incoming = new TradeContractDO();
        incoming.setId(20L);
        incoming.setCompanyId(9L);
        incoming.setCounterpartyCompanyId(3L);
        incoming.setContractNo("HT-TEST");
        incoming.setStatus("PENDING");
        when(contractMapper.selectOne(any(Wrapper.class))).thenReturn(null, incoming, incoming);
        assertThatThrownBy(() -> service.approveContract(20L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("合同不存在或状态不是待审批");
        assertThat(service.approveContract(20L)).isEqualTo("合同已签署生效");
        verify(contractArchiveService).archiveOnApproval(any(ContractRespDTO.class),
                org.mockito.ArgumentMatchers.eq(7L));
        verify(approvalService).recordResult(9L, 3L, "CONTRACT", 20L, 20L,
                "APPROVED", "合同已签署并生效", "对方已签署合同 HT-TEST", null);
        verify(approvalService).recordResult(3L, 9L, "CONTRACT", 20L, 20L,
                "APPROVED", "已同意签署对方企业的HT-TEST合同", "我方已签署合同 HT-TEST", null);
        assertThat(service.rejectContract(20L)).isEqualTo("合同已拒绝");
        verify(approvalService).recordResult(9L, 3L, "CONTRACT", 20L, 20L,
                "REJECTED", "合同已被拒绝", "对方已拒绝合同 HT-TEST", null);
        verify(approvalService).recordResult(3L, 9L, "CONTRACT", 20L, null,
                "REJECTED", "已拒绝对方企业的HT-TEST合同", "我方已拒绝合同 HT-TEST", null);
    }

    @Test
    void initiatorCanCancelResubmitAndDeleteInactiveContract() {
        TradeContractDO outgoing = new TradeContractDO();
        outgoing.setId(41L);
        outgoing.setCompanyId(3L);
        outgoing.setCounterpartyCompanyId(9L);
        outgoing.setCounterpartyName("对方企业");
        outgoing.setContractNo("HT-41");
        outgoing.setDirection("SALE");
        outgoing.setName("原合同");
        outgoing.setStatus("PENDING");
        outgoing.setInitiatorHidden(false);
        outgoing.setVersionNo(1);
        when(contractMapper.selectOne(any(Wrapper.class))).thenReturn(outgoing);
        when(contractMapper.update(any(Wrapper.class))).thenReturn(1);

        assertThat(service.cancelContract(41L)).isEqualTo("合同审批已撤回");
        var cancellationOrder = org.mockito.Mockito.inOrder(signingCancellation, contractMapper);
        cancellationOrder.verify(signingCancellation).cancelForChange(outgoing, "发起方撤回合同");
        cancellationOrder.verify(contractMapper).update(any(Wrapper.class));
        verify(approvalService).recordResult(9L, 3L, "CONTRACT", 41L, null,
                "CANCELLED", "合同已被发起方撤回", "发起方已撤回合同 HT-41", null);

        CompanyRespDTO initiator = new CompanyRespDTO();
        initiator.setId(3L);
        initiator.setName("当前企业");
        CompanyRespDTO counterparty = new CompanyRespDTO();
        counterparty.setId(9L);
        counterparty.setName("对方企业");
        when(companyMapper.selectById(3L)).thenReturn(initiator);
        when(companyMapper.selectById(9L)).thenReturn(counterparty);
        when(relationMapper.countActiveBetween(3L, 9L)).thenReturn(1L);
        when(contractMapper.selectById(41L)).thenReturn(outgoing);
        CreateContractReqVO changed = new CreateContractReqVO(null, "修改后合同", "新模板",
                new BigDecimal("1200"), "2026-08-19", null, "{}",
                9L, "SALE", null, "resubmit-41");

        ContractRespDTO resubmitted = service.resubmitContract(41L, changed);
        assertThat(resubmitted.id()).isEqualTo("41");
        verify(signingCancellation).cancelForChange(outgoing, "合同修改，终止旧版本签署");

        outgoing.setStatus("REJECTED");
        assertThat(service.deleteContract(41L)).isEqualTo("合同已从我方列表删除");
    }

    @Test
    void deniedContractChangesCannotCancelExternalSigning() {
        org.mockito.Mockito.doThrow(new BusinessException("无合同权限"))
                .when(accessControl).requirePermission(3L, "contract_sign");
        assertThatThrownBy(() -> service.cancelContract(41L)).hasMessage("无合同权限");
        assertThatThrownBy(() -> service.rejectContract(41L)).hasMessage("无合同权限");
        org.mockito.Mockito.verifyNoInteractions(signingCancellation);
    }

    @Test
    void foreignContractChangesCannotCancelExternalSigning() {
        when(contractMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        assertThatThrownBy(() -> service.cancelContract(41L)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.rejectContract(41L)).isInstanceOf(BusinessException.class);
        org.mockito.Mockito.verifyNoInteractions(signingCancellation);
    }

    @Test
    void electronicActivationRejectsOldVersionEvenIfContractAlreadyActive() {
        TradeContractDO contract = new TradeContractDO(); contract.setId(41L); contract.setVersionNo(2); contract.setStatus("ACTIVE");
        when(contractMapper.selectByIdForUpdate(41L)).thenReturn(contract);
        assertThatThrownBy(() -> service.activateAfterElectronicSignature(41L, 1, 7L)).hasMessageContaining("版本不一致");
        assertThatThrownBy(() -> service.voidAfterElectronicAbolish(41L, 1, 7L)).hasMessageContaining("版本不一致");
        verify(contractMapper, org.mockito.Mockito.never()).update(any(Wrapper.class));
    }

    @Test
    void initiatorCanDeleteCancelledContract() {
        TradeContractDO outgoing = new TradeContractDO();
        outgoing.setId(42L);
        outgoing.setCompanyId(3L);
        outgoing.setCounterpartyCompanyId(9L);
        outgoing.setContractNo("HT-42");
        outgoing.setStatus("CANCELLED");
        outgoing.setInitiatorHidden(false);
        when(contractMapper.selectOne(any(Wrapper.class))).thenReturn(outgoing);
        when(contractMapper.update(any(Wrapper.class))).thenReturn(1);

        assertThat(service.deleteContract(42L)).isEqualTo("合同已从我方列表删除");
        verify(auditLogService).log(3L, "CONTRACT", 42L, "DELETE",
                "从发起方合同列表删除合同 HT-42");
    }

    @Test
    void rejectedContractStaysOnlyInInitiatorListUntilLocallyDeleted() {
        TradeContractDO outgoing = new TradeContractDO();
        outgoing.setId(51L);
        outgoing.setCompanyId(3L);
        outgoing.setCounterpartyCompanyId(9L);
        outgoing.setCounterpartyName("拒绝方");
        outgoing.setName("供货合同");
        outgoing.setStatus("REJECTED");
        outgoing.setInitiatorHidden(false);

        TradeContractDO incoming = new TradeContractDO();
        incoming.setId(52L);
        incoming.setCompanyId(9L);
        incoming.setCounterpartyCompanyId(3L);
        incoming.setName("对方合同");
        incoming.setStatus("REJECTED");

        TradeContractDO hidden = new TradeContractDO();
        hidden.setId(53L);
        hidden.setCompanyId(3L);
        hidden.setCounterpartyCompanyId(9L);
        hidden.setStatus("REJECTED");
        hidden.setInitiatorHidden(true);

        when(contractMapper.selectById(51L)).thenReturn(outgoing);
        when(contractMapper.selectById(52L)).thenReturn(incoming);
        when(contractMapper.selectById(53L)).thenReturn(hidden);

        assertThat(service.getContract(51L).status()).isEqualTo("REJECTED");
        assertThatThrownBy(() -> service.getContract(52L))
                .isInstanceOf(BusinessException.class).hasMessage("合同不存在");
        assertThatThrownBy(() -> service.getContract(53L))
                .isInstanceOf(BusinessException.class).hasMessage("合同不存在");
    }

    @Test
    @SuppressWarnings("unchecked")
    void pendingContractsStayScopedToCurrentCompany() {
        AuthContext.set(7L, null);
        assertThat(service.pendingContracts()).isEmpty();

        when(contractMapper.update(any(Wrapper.class))).thenReturn(1);
        AuthContext.set(7L, 3L);
        assertThat(service.pendingContracts()).isEmpty();

        TradeContractDO pending = new TradeContractDO();
        pending.setId(1L);
        pending.setCompanyId(9L);
        pending.setCounterpartyCompanyId(3L);
        pending.setCounterpartyName("当前企业");
        pending.setName("待签合同");
        pending.setStatus("PENDING");
        pending.setInitiatedBy(8L);
        when(accessControl.hasPermission(3L, "contract_sign")).thenReturn(true);
        when(contractMapper.selectContractsAwaitingSignature(3L)).thenReturn(List.of(pending));

        assertThat(service.pendingContracts()).extracting(ContractRespDTO::id).containsExactly("1");
    }

    @Test
    void sharedLedgerUsesTheReceivingCompanyPerspective() {
        TradeContractDO incoming = new TradeContractDO();
        incoming.setId(31L);
        incoming.setCompanyId(9L);
        incoming.setCounterpartyCompanyId(3L);
        incoming.setCounterpartyName("当前企业");
        incoming.setDirection("SALE");
        incoming.setName("供货合同");
        incoming.setAmount(BigDecimal.TEN);
        incoming.setStatus("ACTIVE");
        CompanyRespDTO initiator = new CompanyRespDTO();
        initiator.setId(9L);
        initiator.setName("供应商");
        when(companyMapper.selectById(9L)).thenReturn(initiator);
        when(contractMapper.countPartyContracts(3L, null, null)).thenReturn(1L);
        when(contractMapper.selectPartyContracts(3L, null, null, 20, 0L)).thenReturn(List.of(incoming));

        ContractRespDTO view = service.pageContracts(null, null, 1, 20).items().get(0);

        assertThat(view.viewerCounterpartyCompanyId()).isEqualTo("9");
        assertThat(view.viewerCounterpartyName()).isEqualTo("供应商");
        assertThat(view.viewerDirection()).isEqualTo("PURCHASE");
        assertThat(view.perspective()).isEqualTo("INCOMING");
    }
}
