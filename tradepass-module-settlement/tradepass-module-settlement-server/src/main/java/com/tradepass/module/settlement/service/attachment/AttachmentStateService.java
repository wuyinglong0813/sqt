package com.tradepass.module.settlement.service.attachment;

import com.tradepass.module.settlement.api.attachment.AttachmentStateOperations;
import com.tradepass.module.settlement.api.attachment.AttachmentStateOperations.*;
import com.tradepass.module.settlement.dal.dataobject.attachment.ContractAttachmentDO;
import com.tradepass.module.settlement.dal.mysql.attachment.ContractAttachmentMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

public interface AttachmentStateService {
    AttachmentState state(Long id, boolean includeDeleted);
    long effectiveCount(Long contractId);
    long unfinishedCount(Long contractId);
    int voidApproved(Long id);
    long pendingConfirmationCount(long companyId);
}
