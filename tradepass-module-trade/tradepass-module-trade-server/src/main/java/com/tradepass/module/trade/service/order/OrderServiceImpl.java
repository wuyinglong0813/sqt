package com.tradepass.module.trade.service.order;

import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.module.identity.api.permission.AccessControlOperations.*;
import com.tradepass.framework.audit.core.AuditLogService;
import com.tradepass.module.trade.service.ranking.RankingCacheService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.module.trade.controller.app.order.vo.CreateOrderReqVO;
import com.tradepass.framework.common.pojo.PagePayload;
import com.tradepass.module.trade.api.order.dto.TradeOrderRespDTO;
import com.tradepass.module.trade.dal.dataobject.order.TradeOrderDO;
import com.tradepass.module.trade.dal.mysql.order.TradeOrderMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class OrderServiceImpl implements OrderService {
    private final TradeOrderMapper tradeOrderMapper;
    private final AccessControlOperations accessControlService;
    private final AuditLogService auditLogService;
    private final RankingCacheService rankingCache;

    public OrderServiceImpl(TradeOrderMapper tradeOrderMapper, AccessControlOperations accessControlService,
                        AuditLogService auditLogService, RankingCacheService rankingCache) {
        this.tradeOrderMapper = tradeOrderMapper;
        this.accessControlService = accessControlService;
        this.auditLogService = auditLogService;
        this.rankingCache = rankingCache;
    }

    public List<TradeOrderRespDTO> listOrders(String counterpartyName) {
        long companyId = AuthContext.requireCompanyId();
        accessControlService.requireAnyPermission(companyId, "order_view", "reconciliation");
        LambdaQueryWrapper<TradeOrderDO> query = new LambdaQueryWrapper<TradeOrderDO>()
                .eq(TradeOrderDO::getCompanyId, companyId)
                .orderByDesc(TradeOrderDO::getOrderDate);
        if (counterpartyName != null && !counterpartyName.isBlank()) {
            query.eq(TradeOrderDO::getCounterpartyName, counterpartyName);
        }
        return tradeOrderMapper.selectList(query).stream().map(this::toOrderPayload).toList();
    }

    public PagePayload<TradeOrderRespDTO> pageOrders(String counterpartyName, String direction, int page, int size) {
        int normalizedPage = normalizePage(page);
        int normalizedSize = normalizeSize(size);
        long companyId = AuthContext.requireCompanyId();
        accessControlService.requireAnyPermission(companyId, "order_view", "reconciliation");
        long total = tradeOrderMapper.selectCount(orderQuery(companyId, counterpartyName, direction));
        List<TradeOrderRespDTO> items = tradeOrderMapper.selectList(orderQuery(companyId, counterpartyName, direction)
                        .last(limitClause(normalizedPage, normalizedSize)))
                .stream().map(this::toOrderPayload).toList();
        return PagePayload.of(items, total, normalizedPage, normalizedSize);
    }

    public Map<String, Object> orderSummary(String counterpartyName, String direction) {
        long companyId = AuthContext.requireCompanyId();
        accessControlService.requireAnyPermission(companyId, "order_view", "reconciliation");
        validateDirection(direction, false);
        return tradeOrderMapper.selectOrderSummary(companyId, trim(counterpartyName), trim(direction));
    }

    public List<Map<String, Object>> monthlyOrderSummary(String counterpartyName, String direction) {
        String cleanName = trim(counterpartyName);
        String cleanDirection = trim(direction);
        if (cleanName == null || cleanName.isBlank() || cleanDirection == null || cleanDirection.isBlank()) {
            throw new BusinessException("合作方和交易方向不能为空");
        }
        long companyId = AuthContext.requireCompanyId();
        accessControlService.requireAnyPermission(companyId, "order_view", "reconciliation");
        validateDirection(cleanDirection, true);
        return tradeOrderMapper.selectMonthlyOrderSummary(
                companyId, cleanName, cleanDirection);
    }

    @Transactional
    public TradeOrderRespDTO createOrder(CreateOrderReqVO request) {
        long companyId = AuthContext.requireCompanyId();
        accessControlService.requirePermission(companyId, "order_create");
        validateDirection(request.direction(), true);
        if (request.clientRequestId() != null && !request.clientRequestId().isBlank()) {
            TradeOrderDO existing = tradeOrderMapper.selectOne(new LambdaQueryWrapper<TradeOrderDO>()
                    .eq(TradeOrderDO::getCompanyId, companyId)
                    .eq(TradeOrderDO::getClientRequestId, request.clientRequestId().trim())
                    .last("LIMIT 1"));
            if (existing != null) {
                return toOrderPayload(existing);
            }
        }
        BigDecimal amount = requireNonNegativeAmount(request.amount(), "订单金额");
        TradeOrderDO order = new TradeOrderDO();
        order.setCompanyId(companyId);
        order.setCounterpartyCompanyId(request.counterpartyCompanyId());
        order.setDirection(request.direction());
        order.setCounterpartyName(request.counterpartyName().trim());
        order.setOrderNo(normalizeBusinessNo(request.orderNo(), "ORD"));
        order.setClientRequestId(trim(request.clientRequestId()));
        order.setAmount(amount);
        order.setOrderDate(request.orderDate());
        order.setStatus("CONFIRMED");
        order.setCreatedBy(AuthContext.userId());
        tradeOrderMapper.insert(order);
        if (rankingCache != null) {
            rankingCache.evict(companyId, request.direction().toUpperCase());
        }
        auditLogService.log(companyId, "ORDER", order.getId(), "CREATE",
                "创建订单 " + order.getOrderNo() + "，金额 " + amount);
        return toOrderPayload(order);
    }

    private LambdaQueryWrapper<TradeOrderDO> orderQuery(long companyId, String counterpartyName, String direction) {
        LambdaQueryWrapper<TradeOrderDO> query = new LambdaQueryWrapper<TradeOrderDO>()
                .eq(TradeOrderDO::getCompanyId, companyId)
                .orderByDesc(TradeOrderDO::getOrderDate);
        String cleanName = trim(counterpartyName);
        if (cleanName != null && !cleanName.isBlank()) {
            query.eq(TradeOrderDO::getCounterpartyName, cleanName);
        }
        String cleanDirection = trim(direction);
        if (cleanDirection != null && !cleanDirection.isBlank()) {
            query.eq(TradeOrderDO::getDirection, cleanDirection);
        }
        return query;
    }

    private int normalizePage(int page) {
        return Math.max(1, page);
    }

    private int normalizeSize(int size) {
        return Math.max(1, Math.min(size, 100));
    }

    private String limitClause(int page, int size) {
        long offset = (long) (page - 1) * size;
        return "LIMIT " + size + " OFFSET " + offset;
    }

    private TradeOrderRespDTO toOrderPayload(TradeOrderDO order) {
        return new TradeOrderRespDTO(
                idString(order.getId()),
                order.getDirection(),
                order.getCounterpartyName(),
                order.getOrderNo(),
                order.getAmount(),
                order.getOrderDate(),
                order.getStatus()
        );
    }

    private BigDecimal requireNonNegativeAmount(BigDecimal amount, String label) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(label + "不能为负数");
        }
        return amount.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private void validateDirection(String direction, boolean required) {
        if (direction == null || direction.isBlank()) {
            if (required) {
                throw new BusinessException("交易方向不能为空");
            }
            return;
        }
        if (!"SALE".equalsIgnoreCase(direction) && !"PURCHASE".equalsIgnoreCase(direction)) {
            throw new BusinessException("交易方向只能是 SALE 或 PURCHASE");
        }
    }

    private String normalizeBusinessNo(String requested, String prefix) {
        if (requested != null && !requested.isBlank()) {
            return requested.trim();
        }
        String day = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        return prefix + "-" + day + "-" + suffix;
    }

    private String idString(Long id) {
        return id == null ? null : String.valueOf(id);
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }
}
