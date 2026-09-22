package com.tradepass.module.identity.api.company;

import com.tradepass.module.identity.api.company.dto.CompanyRespDTO;
import com.tradepass.module.identity.convert.company.CompanyConvert;
import com.tradepass.module.identity.dal.mysql.company.CompanyMapper;
import org.springframework.stereotype.Service;

@Service
public class CompanyReaderImpl implements CompanyReader {
    private final CompanyMapper mapper;
    public CompanyReaderImpl(CompanyMapper mapper) { this.mapper = mapper; }
    @Override public CompanyRespDTO selectById(java.io.Serializable id) { return CompanyConvert.INSTANCE.toDTO(mapper.selectById(id)); }
}
