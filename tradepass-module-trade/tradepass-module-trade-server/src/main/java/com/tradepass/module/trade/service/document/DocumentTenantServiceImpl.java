package com.tradepass.module.trade.service.document;

import com.tradepass.module.trade.api.document.DocumentTenantOperations;
import com.tradepass.module.trade.api.document.DocumentTenantOperations.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tradepass.module.trade.dal.dataobject.document.BusinessDocumentTemplateDO;
import com.tradepass.module.trade.dal.mysql.document.BusinessDocumentTemplateMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentTenantServiceImpl implements DocumentTenantService {
    private final BusinessDocumentTemplateMapper businessDocumentTemplateMapper;
    public DocumentTenantServiceImpl(BusinessDocumentTemplateMapper templates) { this.businessDocumentTemplateMapper = templates; }
    @Transactional
    public void initialize(long companyId, long operatorUserId) {
        seedDocumentTemplate(companyId, operatorUserId, "SALES_ORDER", "标准销售单模板",
                "{\"columns\":[\"序号\",\"品名\",\"规格\",\"单位\",\"数量\",\"单价\",\"金额\",\"备注\"],\"blankRows\":8}");
        seedDocumentTemplate(companyId, operatorUserId, "RETURN_ORDER", "标准退货单模板",
                "{\"columns\":[\"序号\",\"品名\",\"规格\",\"单位\",\"数量\",\"单价\",\"金额\",\"退货原因\"],\"blankRows\":8}");
    }

    private void seedDocumentTemplate(long companyId, long operatorUserId, String type, String name, String content) {
        if (businessDocumentTemplateMapper.selectCount(new LambdaQueryWrapper<BusinessDocumentTemplateDO>()
                .eq(BusinessDocumentTemplateDO::getCompanyId, companyId)
                .eq(BusinessDocumentTemplateDO::getDocumentType, type)
                .eq(BusinessDocumentTemplateDO::getName, name)) > 0) {
            return;
        }
        BusinessDocumentTemplateDO template = new BusinessDocumentTemplateDO();
        template.setCompanyId(companyId);
        template.setDocumentType(type);
        template.setName(name);
        template.setContent(content);
        template.setCreatedBy(operatorUserId);
        businessDocumentTemplateMapper.insert(template);
    }

}
