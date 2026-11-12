package com.tradepass.module.identity.api.company;

import com.tradepass.module.identity.api.company.dto.CompanyRespDTO;
import com.tradepass.module.identity.convert.company.CompanyConvert;
import com.tradepass.module.identity.dal.mysql.company.CompanyMapper;
import com.tradepass.module.identity.dal.mysql.company.CompanyReferralMapper;
import org.springframework.stereotype.Service;

import java.io.Serializable;
import java.util.Locale;

@Service
public class CompanyReaderImpl implements CompanyReader {
    private final CompanyMapper mapper;
    private final CompanyReferralMapper referrals;

    public CompanyReaderImpl(CompanyMapper mapper, CompanyReferralMapper referrals) {
        this.mapper = mapper;
        this.referrals = referrals;
    }

    @Override
    public CompanyRespDTO selectById(Serializable id) {
        CompanyRespDTO company = CompanyConvert.INSTANCE.toDTO(mapper.selectById(id));
        if (company != null && company.getCreditCode() != null && !company.getCreditCode().isBlank()) {
            String subject = company.getCreditCode().trim().toUpperCase(Locale.ROOT);
            company.setConfirmedReferralCount(referrals.countConfirmed(subject));
        }
        return company;
    }
}
