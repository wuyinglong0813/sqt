package com.tradepass.module.contract.service.abolish;

import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.module.contract.dal.dataobject.signing.FadadaContractSignTaskDO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

public interface ContractAbolishIntentService {
    public record PendingIntent(String id, String previousTaskId) {}

    String begin(FadadaContractSignTaskDO task);
    List<PendingIntent> pending(FadadaContractSignTaskDO task);
    void confirm(String intentId, String childId);
}
