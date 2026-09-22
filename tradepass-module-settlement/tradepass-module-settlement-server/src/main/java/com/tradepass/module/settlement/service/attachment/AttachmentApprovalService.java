package com.tradepass.module.settlement.service.attachment;

import com.tradepass.module.settlement.api.attachment.ContractAttachmentOperations;
import com.tradepass.module.settlement.api.attachment.ContractAttachmentOperations.*;
import com.tradepass.module.settlement.api.attachment.AttachmentApprovalOperations;
import com.tradepass.module.settlement.api.attachment.AttachmentApprovalOperations.*;
import com.tradepass.module.identity.api.directory.IdentityDirectoryOperations;
import com.tradepass.module.identity.api.directory.IdentityDirectoryOperations.*;
import com.tradepass.module.contract.api.contract.ContractReader;
import com.tradepass.module.contract.api.contract.ContractReader.*;
import com.tradepass.module.settlement.dal.mysql.attachment.ContractAttachmentMapper;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

public interface AttachmentApprovalService {
    List<PendingAttachment> pendingAttachments(long companyId);
}
