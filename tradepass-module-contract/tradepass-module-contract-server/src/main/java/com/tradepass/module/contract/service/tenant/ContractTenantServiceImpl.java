package com.tradepass.module.contract.service.tenant;

import com.tradepass.module.contract.api.tenant.ContractTenantOperations;
import com.tradepass.module.contract.api.tenant.ContractTenantOperations.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tradepass.module.contract.dal.dataobject.template.ContractTemplateDO;
import com.tradepass.module.contract.dal.dataobject.template.TemplateCategoryDO;
import com.tradepass.module.contract.dal.mysql.template.ContractTemplateMapper;
import com.tradepass.module.contract.dal.mysql.template.TemplateCategoryMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContractTenantServiceImpl implements ContractTenantService {
    private static final String STANDARD_CONTRACT_TEMPLATE = """
            {"title":"购销合同","fields":[
              {"key":"supplier","label":"供方","editable":false,"value":""},
              {"key":"buyer","label":"需方","editable":false,"value":""},
              {"key":"signDate","label":"签订日期","type":"date","editable":true,"value":""}
            ],"sections":[
              {"title":"商品明细","type":"table","columns":["品名","规格","单位","数量","单价","金额"],"rows":[]},
              {"title":"质量与验收","type":"clause","content":"双方按合同约定的质量标准和验收方式执行。"},
              {"title":"争议解决","type":"clause","content":"争议由双方协商解决；协商不成时依法处理。"}
            ]}
            """;

    private final TemplateCategoryMapper templateCategoryMapper;
    private final ContractTemplateMapper contractTemplateMapper;
    public ContractTenantServiceImpl(TemplateCategoryMapper categories, ContractTemplateMapper templates) {
        this.templateCategoryMapper = categories;
        this.contractTemplateMapper = templates;
    }
    @Transactional
    public void initialize(long companyId, long operatorUserId) {
        seedCategory(companyId, "采购", 1);
        seedCategory(companyId, "供货", 2);
        seedCategory(companyId, "交易", 3);
        seedCategory(companyId, "服务", 4);
        seedContractTemplate(companyId, operatorUserId);
    }

    private void seedCategory(long companyId, String name, int sortOrder) {
        if (templateCategoryMapper.selectCount(new LambdaQueryWrapper<TemplateCategoryDO>()
                .eq(TemplateCategoryDO::getCompanyId, companyId)
                .eq(TemplateCategoryDO::getName, name)) > 0) {
            return;
        }
        TemplateCategoryDO category = new TemplateCategoryDO();
        category.setCompanyId(companyId);
        category.setName(name);
        category.setSortOrder(sortOrder);
        templateCategoryMapper.insert(category);
    }

    private void seedContractTemplate(long companyId, long operatorUserId) {
        String name = "标准购销合同模板";
        if (contractTemplateMapper.selectCount(new LambdaQueryWrapper<ContractTemplateDO>()
                .eq(ContractTemplateDO::getCompanyId, companyId)
                .eq(ContractTemplateDO::getName, name)) > 0) {
            return;
        }
        ContractTemplateDO template = new ContractTemplateDO();
        template.setCompanyId(companyId);
        template.setName(name);
        template.setCategory("交易");
        template.setContent(STANDARD_CONTRACT_TEMPLATE);
        template.setCreatedBy(operatorUserId);
        template.setUpdatedBy(operatorUserId);
        contractTemplateMapper.insert(template);
    }

}
