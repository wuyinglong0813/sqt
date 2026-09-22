package com.tradepass.module.trade.service.document;

import com.tradepass.module.trade.api.document.DocumentTenantOperations;
import com.tradepass.module.trade.api.document.DocumentTenantOperations.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tradepass.module.trade.dal.dataobject.document.BusinessDocumentTemplateDO;
import com.tradepass.module.trade.dal.mysql.document.BusinessDocumentTemplateMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

public interface DocumentTenantService {
    void initialize(long companyId, long operatorUserId);
}
