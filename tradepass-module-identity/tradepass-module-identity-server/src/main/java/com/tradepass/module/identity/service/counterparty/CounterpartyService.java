package com.tradepass.module.identity.service.counterparty;

import com.tradepass.framework.common.pojo.TradePassDtos;
import com.tradepass.module.identity.service.permission.AccessControlService;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.common.pojo.TradePassDtos.CounterpartyRelation;
import com.tradepass.module.identity.controller.app.counterparty.vo.AddCounterpartyReqVO;
import com.tradepass.module.identity.dal.mysql.counterparty.CounterpartyRelationMapper;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;

public interface CounterpartyService {
    List<CounterpartyRelation> listCounterparties(String companyId, String role);
    CounterpartyRelation addCounterparty(AddCounterpartyReqVO request);
}
