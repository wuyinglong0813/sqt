package com.tradepass.module.contract.service.contract;

import com.tradepass.module.contract.service.archive.ContractArchiveService;
import com.tradepass.module.identity.api.counterparty.CounterpartyReaderImpl;
import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.module.identity.controller.app.counterparty.vo.AddCounterpartyReqVO;
import com.tradepass.module.identity.dal.mysql.counterparty.CounterpartyRelationMapper;
import com.tradepass.module.identity.service.counterparty.CounterpartyService;
import com.tradepass.module.identity.service.counterparty.CounterpartyServiceImpl;
import com.tradepass.module.identity.service.permission.AccessControlService;
import com.tradepass.module.trade.api.ranking.RankingCacheOperations;
import com.tradepass.module.trade.api.ranking.RankingCacheOperationsImpl;
import com.tradepass.framework.audit.core.AuditLogService;
import com.tradepass.framework.common.pojo.PagePayload;
import com.tradepass.framework.common.pojo.TradePassDtos.CounterpartyRelation;
import com.tradepass.module.contract.dal.mysql.contract.TradeContractMapper;
import com.tradepass.module.contract.dal.mysql.template.ContractTemplateMapper;
import com.tradepass.module.contract.dal.mysql.template.TemplateCategoryMapper;
import com.tradepass.module.identity.api.company.CompanyReader;
import com.tradepass.module.trade.api.order.dto.TradeOrderRespDTO;
import com.tradepass.module.trade.controller.app.order.vo.CreateOrderReqVO;
import com.tradepass.module.trade.dal.mysql.order.TradeOrderMapper;
import com.tradepass.module.trade.service.order.OrderService;
import com.tradepass.module.trade.service.order.OrderServiceImpl;
import com.tradepass.module.trade.service.ranking.RankingCacheService;

import java.util.List;
import java.util.Map;

/** Test fixture adapter: assertions exercise the actual extracted domain services. */
final class LegacyMixedTradeService extends TradeServiceImpl {
    private final OrderService orders;
    private final CounterpartyService counterparties;

    public LegacyMixedTradeService(TradeOrderMapper tradeOrderMapper,
                        CounterpartyRelationMapper counterpartyRelationMapper,
                        TemplateCategoryMapper templateCategoryMapper,
                        ContractTemplateMapper contractTemplateMapper,
                        TradeContractMapper tradeContractMapper,
                        CompanyReader companyMapper,
                        AccessControlOperations accessControlService,
                        AuditLogService auditLogService,
                        RankingCacheService rankingCache,
                        ContractArchiveService contractArchiveService) {
        this(tradeOrderMapper, counterpartyRelationMapper, templateCategoryMapper, contractTemplateMapper,
                tradeContractMapper, companyMapper, accessControlService,
                accessControlService instanceof AccessControlService service ? service : null,
                auditLogService, rankingCache, contractArchiveService);
    }

    LegacyMixedTradeService(TradeOrderMapper tradeOrderMapper,
                 CounterpartyRelationMapper counterpartyRelationMapper,
                 TemplateCategoryMapper templateCategoryMapper,
                 ContractTemplateMapper contractTemplateMapper,
                 TradeContractMapper tradeContractMapper,
                 CompanyReader companyMapper,
                 AccessControlOperations accessControlOperations,
                 AccessControlService accessControlService,
                 AuditLogService auditLogService,
                 RankingCacheService rankingCache,
                 ContractArchiveService contractArchiveService) {
        super(new CounterpartyReaderImpl(counterpartyRelationMapper), templateCategoryMapper, contractTemplateMapper,
                tradeContractMapper, companyMapper, accessControlOperations, auditLogService,
                rankingOps(rankingCache), contractArchiveService);
        this.orders = new OrderServiceImpl(tradeOrderMapper, accessControlOperations, auditLogService, rankingCache);
        this.counterparties = new CounterpartyServiceImpl(counterpartyRelationMapper, accessControlService);
    }

    LegacyMixedTradeService(TradeOrderMapper tradeOrderMapper,
                 CounterpartyRelationMapper counterpartyRelationMapper,
                 TemplateCategoryMapper templateCategoryMapper,
                 ContractTemplateMapper contractTemplateMapper,
                 TradeContractMapper tradeContractMapper,
                 CompanyReader companyMapper,
                 AccessControlOperations accessControlService,
                 AuditLogService auditLogService,
                 RankingCacheService rankingCache) {
        this(tradeOrderMapper, counterpartyRelationMapper, templateCategoryMapper, contractTemplateMapper,
                tradeContractMapper, companyMapper, accessControlService, auditLogService, rankingCache, null);
    }

    LegacyMixedTradeService(TradeOrderMapper tradeOrderMapper,
                 CounterpartyRelationMapper counterpartyRelationMapper,
                 TemplateCategoryMapper templateCategoryMapper,
                 ContractTemplateMapper contractTemplateMapper,
                 TradeContractMapper tradeContractMapper,
                 CompanyReader companyMapper,
                 AccessControlOperations accessControlService,
                 AuditLogService auditLogService) {
        this(tradeOrderMapper, counterpartyRelationMapper, templateCategoryMapper, contractTemplateMapper,
                tradeContractMapper, companyMapper, accessControlService, auditLogService, null, null);
    }

    public List<TradeOrderRespDTO> listOrders(String counterpartyName) { return orders.listOrders(counterpartyName); }

    public PagePayload<TradeOrderRespDTO> pageOrders(String counterpartyName, String direction, int page, int size) { return orders.pageOrders(counterpartyName, direction, page, size); }

    public Map<String, Object> orderSummary(String counterpartyName, String direction) { return orders.orderSummary(counterpartyName, direction); }

    public List<Map<String, Object>> monthlyOrderSummary(String counterpartyName, String direction) { return orders.monthlyOrderSummary(counterpartyName, direction); }

    public TradeOrderRespDTO createOrder(CreateOrderReqVO request) { return orders.createOrder(request); }

    public List<CounterpartyRelation> listCounterparties(String companyId, String role) { return counterparties.listCounterparties(companyId, role); }

    public CounterpartyRelation addCounterparty(AddCounterpartyReqVO request) { return counterparties.addCounterparty(request); }

    private static RankingCacheOperations rankingOps(RankingCacheService rankingCache) {
        if (rankingCache == null) {
            return null;
        }
        return rankingCache instanceof RankingCacheOperations operations
                ? operations
                : new RankingCacheOperationsImpl(rankingCache);
    }
}
