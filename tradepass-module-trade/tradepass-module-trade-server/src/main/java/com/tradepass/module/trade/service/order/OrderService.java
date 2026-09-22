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

public interface OrderService {
    List<TradeOrderRespDTO> listOrders(String counterpartyName);
    PagePayload<TradeOrderRespDTO> pageOrders(String counterpartyName, String direction, int page, int size);
    Map<String, Object> orderSummary(String counterpartyName, String direction);
    List<Map<String, Object>> monthlyOrderSummary(String counterpartyName, String direction);
    TradeOrderRespDTO createOrder(CreateOrderReqVO request);
}
