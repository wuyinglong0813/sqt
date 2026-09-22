package com.tradepass.module.identity.api.company;

import com.tradepass.module.identity.api.company.dto.CompanyRespDTO;
import java.io.Serializable;

/** Live identity data; callers never open the identity database. */
public interface CompanyReader {
    public CompanyRespDTO selectById(Serializable id);
}
