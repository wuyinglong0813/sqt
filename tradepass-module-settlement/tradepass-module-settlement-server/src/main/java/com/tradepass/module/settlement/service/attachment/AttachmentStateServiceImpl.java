package com.tradepass.module.settlement.service.attachment;

import com.tradepass.module.settlement.api.attachment.AttachmentStateOperations;
import com.tradepass.module.settlement.api.attachment.AttachmentStateOperations.*;
import com.tradepass.module.settlement.dal.dataobject.attachment.ContractAttachmentDO;
import com.tradepass.module.settlement.dal.mysql.attachment.ContractAttachmentMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AttachmentStateServiceImpl implements AttachmentStateService {
    private final ContractAttachmentMapper attachmentMapper;
    public AttachmentStateServiceImpl(ContractAttachmentMapper attachmentMapper) { this.attachmentMapper = attachmentMapper; }
    public AttachmentState state(Long id, boolean includeDeleted) {
        ContractAttachmentDO row = includeDeleted
                ? attachmentMapper.selectStateIncludeDeleted(id)
                : attachmentMapper.selectState(id);
        return row == null ? null : new AttachmentState(
                row.getContractId(), row.getStatus(), row.getCategory(), row.getOriginalName());
    }
    public long effectiveCount(Long contractId) {
        Long count = attachmentMapper.countEffective(contractId);
        return count == null ? 0L : count;
    }
    @Transactional
    public long unfinishedCount(Long contractId) {
        attachmentMapper.touchUnfinished(contractId);
        Long count = attachmentMapper.countUnfinishedForUpdate(contractId);
        return count == null ? 0L : count;
    }
    @Transactional
    public int voidApproved(Long id) {
        return attachmentMapper.voidApproved(id);
    }
    public long pendingConfirmationCount(long companyId) {
        Long count = attachmentMapper.countPendingConfirmation(companyId);
        return count == null ? 0L : count;
    }
}
