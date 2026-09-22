package com.tradepass.module.trade.api.bilateral;

import com.tradepass.module.contract.api.contract.dto.TradeContractRespDTO;
import java.util.Map;

/** In-process domain contract; implementations retain the original transaction semantics. */
public interface BilateralActionOperations {
    public static final String CONTRACT = "CONTRACT";

    public static final String ATTACHMENT = "ATTACHMENT";

    public static final String BUSINESS_DOCUMENT = "BUSINESS_DOCUMENT";

    public static final String END = "END";

    public static final String VOID = "VOID";

    public static final String RESUME = "RESUME";

    public Map<String, Object> request(String bizType, Long bizId, String actionType,
                                       String reason, boolean riskConfirmed);

    public Map<String, Object> decide(Long id, String decision, String reason);

    public String cancel(Long id);

    public Map<String, Object> active(String bizType, Long bizId);

    public ActionState state(long companyId, String bizType, Long bizId);

    public void requireContractMutable(TradeContractRespDTO contract);

    public boolean isContractReadOnly(TradeContractRespDTO contract);

    public record ActionState(Long id, String actionType, String reason,
                              boolean requesterCompany, boolean approverCompany,
                              boolean requesterUser) {
        public static ActionState empty() {
            return new ActionState(null, "", "", false, false, false);
        }

        public boolean pending() {
            return id != null;
        }
    }
}
