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

@Service
public class MemberRemovalNoticeServiceImpl implements MemberRemovalNoticeService {
    
    private final MemberRemovalNoticeMapper noticeMapper;
    private final CompanyMapper companyMapper;

    public MemberRemovalNoticeServiceImpl(MemberRemovalNoticeMapper noticeMapper, CompanyMapper companyMapper) {
        this.noticeMapper = noticeMapper;
        this.companyMapper = companyMapper;
    }

    public void recordRemoval(long userId, long companyId) {
        CompanyDO company = companyMapper.selectById(companyId);
        MemberRemovalNoticeDO notice = new MemberRemovalNoticeDO();
        notice.setUserId(userId);
        notice.setCompanyId(companyId);
        notice.setCompanyName(company == null ? "企业" + companyId : company.getName());
        notice.setRemovedAt(LocalDateTime.now());
        noticeMapper.insert(notice);
    }

    public List<Notice> pending() {
        return noticeMapper.selectPending(AuthContext.userId()).stream()
                .map(n -> new Notice(String.valueOf(n.getId()), String.valueOf(n.getCompanyId()),
                        n.getCompanyName(), n.getRemovedAt())).toList();
    }

    public void acknowledge(List<String> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 20) throw new BusinessException("请选择有效的通知");
        List<Long> noticeIds;
        try {
            noticeIds = ids.stream().map(Long::parseLong).distinct().toList();
        } catch (RuntimeException e) {
            throw new BusinessException("通知 ID 格式不正确");
        }
        noticeMapper.update(new LambdaUpdateWrapper<MemberRemovalNoticeDO>()
                .eq(MemberRemovalNoticeDO::getUserId, AuthContext.userId())
                .in(MemberRemovalNoticeDO::getId, noticeIds)
                .isNull(MemberRemovalNoticeDO::getAcknowledgedAt)
                .set(MemberRemovalNoticeDO::getAcknowledgedAt, LocalDateTime.now()));
    }
}
