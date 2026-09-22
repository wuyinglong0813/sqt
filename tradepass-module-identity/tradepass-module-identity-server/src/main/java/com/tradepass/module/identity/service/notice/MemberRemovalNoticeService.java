package com.tradepass.module.identity.service.notice;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.module.identity.dal.dataobject.company.CompanyDO;
import com.tradepass.module.identity.dal.dataobject.notice.MemberRemovalNoticeDO;
import com.tradepass.module.identity.dal.mysql.company.CompanyMapper;
import com.tradepass.module.identity.dal.mysql.notice.MemberRemovalNoticeMapper;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.List;

public interface MemberRemovalNoticeService {
    public record Notice(String id, String companyId, String companyName, LocalDateTime removedAt) {}

    void recordRemoval(long userId, long companyId);
    List<Notice> pending();
    void acknowledge(List<String> ids);
}
