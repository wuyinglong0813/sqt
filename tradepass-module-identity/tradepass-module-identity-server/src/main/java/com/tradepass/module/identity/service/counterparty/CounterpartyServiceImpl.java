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

@Service
public class CounterpartyServiceImpl implements CounterpartyService {
    private final CounterpartyRelationMapper counterpartyRelationMapper;
    private final AccessControlService accessControlService;

    public CounterpartyServiceImpl(CounterpartyRelationMapper counterpartyRelationMapper, AccessControlService accessControlService) {
        this.counterpartyRelationMapper = counterpartyRelationMapper;
        this.accessControlService = accessControlService;
    }

    public List<CounterpartyRelation> listCounterparties(String companyId, String role) {
        long cid = accessControlService.resolveCompanyId(companyId);
        if ("supplier".equalsIgnoreCase(role)) {
            accessControlService.requireAnyPermission(cid, "counterparty_view", "supplier_view", "counterparty_manage", "contract_sign");
        } else {
            accessControlService.requireAnyPermission(cid, "counterparty_view", "buyer_view", "order_create", "contract_sign");
        }
        List<Map<String, Object>> rows = "supplier".equalsIgnoreCase(role)
                ? counterpartyRelationMapper.selectSupplierCounterparties(cid)
                : counterpartyRelationMapper.selectBuyerCounterparties(cid);
        return rows.stream()
                .filter(row -> row.get("counterpartyCompanyId") != null)
                .map(row -> new CounterpartyRelation(
                        String.valueOf(row.get("id")),
                        string(row.get("counterpartyCompanyId")),
                        string(row.get("counterpartyName")),
                        string(row.get("relationType")),
                        string(row.get("status"))))
                .toList();
    }

    public CounterpartyRelation addCounterparty(AddCounterpartyReqVO request) {
        long companyId = AuthContext.requireCompanyId();
        accessControlService.requireLegal(companyId);
        throw new BusinessException("请使用合作企业邀请，对方接受后才会建立有效关系");
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
