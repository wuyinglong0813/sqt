package com.tradepass.module.contract.service.membership;

import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.module.contract.service.payment.WechatPayGateway;
import com.tradepass.module.identity.api.company.CompanyReader;
import com.tradepass.module.identity.api.company.dto.CompanyRespDTO;
import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.module.identity.api.user.UserIdentityOperations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

@Service
public class MembershipPurchaseService {
    public record ProductView(String id,String name,String type,int amountFen,int standardAmountFen,int vipDays,
                              long signQuota,int quotaDays,boolean firstOffer,boolean canBuy,String reason) { }
    public record Catalog(String companyId,String companyName,boolean purchaseEnabled,String reason,List<ProductView> products) { }
    public record OrderView(String orderNo,String companyId,String companyName,String productName,String productType,
                            int amountFen,long signQuota,int vipDays,int quotaDays,boolean firstOffer,String status,
                            String statusText,String createdAt,String expiresAt,String paidAt,String vipStartAt,
                            String vipEndAt,String quotaEndAt,boolean canPay,boolean canClose) { }
    public record Checkout(OrderView order,Map<String,String> paymentParams) { }
    private record Order(String no,long companyId,String subject,long buyer,String productId,String name,String type,
                         int amount,boolean first,int vipDays,long quota,int quotaDays,String appId,String mchId,
                         String openid,String status,String prepay,LocalDateTime prepayStarted,String transaction,LocalDateTime created,
                         LocalDateTime expires,LocalDateTime paid,LocalDateTime vipStart,LocalDateTime vipEnd,LocalDateTime quotaEnd) { }
    private final JdbcTemplate jdbc;
    private final MembershipPolicyStore policies;
    private final WechatPayGateway payments;
    private final CompanyReader companies;
    private final AccessControlOperations access;
    private final UserIdentityOperations users;
    private final TransactionTemplate tx;
    private final Clock clock;

    @Autowired public MembershipPurchaseService(JdbcTemplate jdbc,MembershipPolicyStore policies,WechatPayGateway payments,
            CompanyReader companies,AccessControlOperations access,UserIdentityOperations users,PlatformTransactionManager manager) {
        this(jdbc,policies,payments,companies,access,users,manager,Clock.systemUTC());
    }
    public MembershipPurchaseService(JdbcTemplate jdbc,MembershipPolicyStore policies,WechatPayGateway payments,
            CompanyReader companies,AccessControlOperations access,UserIdentityOperations users,PlatformTransactionManager manager,Clock clock) {
        this.jdbc=jdbc; this.policies=policies; this.payments=payments; this.companies=companies; this.access=access;
        this.users=users; this.clock=clock; tx=new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Catalog catalog(String platform) {
        CompanyRespDTO company=company(false);
        return tx.execute(t->{
            var snapshot=policies.current(true);
            String reason=saleReason(company,snapshot,platform);
            List<ProductView> products=new ArrayList<>();
            if (payments.platformAllowed(platform)) for (var product:snapshot.policy().products()) {
                if (!product.onSale()) continue;
                boolean first="VIP".equals(product.type()) && product.firstPriceFen()!=null && firstVip(subject(company));
                String restriction=reason;
                if (restriction.isBlank() && product.vipOnly() && !paidVip(subject(company)))
                    restriction="仅已购VIP会员可购买此增购包";
                products.add(new ProductView(product.id(),product.name(),product.type(),
                        first?product.firstPriceFen():product.priceFen(),product.priceFen(),product.vipDays(),
                        product.signQuota(),product.quotaDays(),first,restriction.isBlank(),restriction));
            }
            return new Catalog(company.getId().toString(),company.getName(),reason.isBlank(),reason,List.copyOf(products));
        });
    }

    public OrderView create(String productId,String idempotencyKey,String platform) {
        if (productId==null || !productId.matches("[A-Za-z0-9_-]{1,48}") || idempotencyKey==null
                || !idempotencyKey.matches("[A-Za-z0-9_-]{8,64}")) throw new BusinessException("购买参数不完整");
        CompanyRespDTO company=company(true);
        String subject=subject(company);
        if (!payments.ready() || !policies.current(false).policy().billingEnabled())
            throw new BusinessException("购买暂未开放");
        if (!payments.platformAllowed(platform)) throw new BusinessException("当前客户端暂未开放企业服务购买");
        var identity=users.currentPaymentIdentity();
        var config=payments.settings();
        if (!config.appId().equals(identity.appId()) || identity.openid()==null || identity.openid().isBlank())
            throw new BusinessException("支付AppID与小程序登录配置不一致，请联系平台");
        return tx.execute(t->{
            var snapshot=policies.current(true);
            var repeated=jdbc.query("""
                    SELECT * FROM membership_purchase_order WHERE subject_key=? AND buyer_id=? AND idempotency_key=?
                    """,this::map,subject,AuthContext.userId(),idempotencyKey);
            if (!repeated.isEmpty()) {
                if (!repeated.get(0).productId().equals(productId)) throw new BusinessException("购买请求已用于其他商品");
                return view(repeated.get(0),company,platform,snapshot);
            }
            String reason=saleReason(company,snapshot,platform);
            if (!reason.isBlank()) throw new BusinessException(reason);
            var product=snapshot.policy().products().stream().filter(p->p.onSale() && p.id().equals(productId))
                    .findFirst().orElseThrow(()->new BusinessException("该套餐已下架，请刷新后选择"));
            if (product.vipOnly() && !paidVip(subject)) throw new BusinessException("此增购包仅供有效付费VIP购买");
            if ("VIP".equals(product.type()) && jdbc.queryForObject("""
                    SELECT COUNT(*) FROM membership_purchase_order WHERE subject_key=? AND product_type='VIP' AND status='PENDING'
                    """,Long.class,subject)>0)
                throw new BusinessException("企业已有待支付会员订单，请先在订单记录中继续或关闭");
            boolean first="VIP".equals(product.type()) && product.firstPriceFen()!=null && firstVip(subject);
            int amount=first?product.firstPriceFen():product.priceFen();
            String no="MP"+UUID.randomUUID().toString().replace("-","").substring(0,30);
            LocalDateTime now=now();
            jdbc.update("""
                    INSERT INTO membership_purchase_order(order_no,company_id,subject_key,buyer_id,idempotency_key,
                      product_id,product_name,product_type,amount_fen,first_offer,vip_days,sign_quota,quota_days,
                      policy_revision,app_id,mch_id,payer_openid,created_at,expires_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,no,company.getId(),subject,AuthContext.userId(),idempotencyKey,product.id(),product.name(),product.type(),
                    amount,first,product.vipDays(),product.signQuota(),product.quotaDays(),snapshot.revision(),
                    config.appId(),config.mchId(),identity.openid(),now,now.plusMinutes(snapshot.policy().orderExpireMinutes()));
            return view(load(no),company,platform,snapshot);
        });
    }

    public List<OrderView> orders(String platform) {
        var company=company(true);
        return tx.execute(t->{
            var snapshot=policies.current(true);
            return jdbc.query("SELECT * FROM membership_purchase_order WHERE subject_key=? ORDER BY created_at DESC,order_no DESC LIMIT 50",
                    this::map,subject(company)).stream().map(o->view(o,company,platform,snapshot)).toList();
        });
    }
    public OrderView detail(String no,String platform) {
        var company=company(true);
        return tx.execute(t->view(owned(no,company),company,platform,policies.current(true)));
    }
    public Checkout prepay(String no,String platform) {
        var company=company(true);
        Order order=tx.execute(t->{
            var snapshot=policies.current(true);
            Order value=owned(no,company);
            if (!view(value,company,platform,snapshot).canPay()) throw new BusinessException("订单当前不能支付，请刷新或关闭订单");
            if (value.prepay()==null || value.prepay().isBlank())
                jdbc.update("UPDATE membership_purchase_order SET prepay_started_at=? WHERE order_no=?",now(),no);
            return load(no);
        });
        String prepay=order.prepay();
        if (prepay==null || prepay.isBlank()) {
            prepay=payments.prepay(order.no(),order.name(),order.amount(),order.openid(),
                    order.expires().atZone(MembershipPolicy.BEIJING).toInstant(),order.appId(),order.mchId());
            String finalPrepay=prepay;
            tx.executeWithoutResult(t->{
                policies.current(true);
                jdbc.update("UPDATE membership_purchase_order SET prepay_id=? WHERE order_no=? AND status='PENDING'",
                        finalPrepay,order.no());
            });
        }
        OrderView latest=detail(no,platform);
        if (!"PENDING".equals(latest.status())) return new Checkout(latest,Map.of());
        return new Checkout(latest,payments.paymentParams(prepay,order.appId(),order.mchId()));
    }
    public OrderView sync(String no,String platform) {
        var company=company(true);
        Order order=tx.execute(t->{ policies.current(true); return owned(no,company); });
        if (!"PENDING".equals(order.status())) return detail(no,platform);
        var transaction=payments.query(order.no(),order.appId(),order.mchId());
        validateReference(order,transaction);
        if ("SUCCESS".equals(transaction.state())) settle(transaction,null,null);
        else if ("CLOSED".equals(transaction.state())) closed(no);
        return detail(no,platform);
    }
    public OrderView close(String no,String platform) {
        var company=company(true);
        Order order=tx.execute(t->{ policies.current(true); return owned(no,company); });
        if (!"PENDING".equals(order.status())) return detail(no,platform);
        if (order.prepayStarted()==null) {
            if (closeUnstarted(no)) return detail(no,platform);
            order=tx.execute(t->{ policies.current(true); return owned(no,company); });
        }
        var result=payments.query(no,order.appId(),order.mchId());
        validateReference(order,result);
        if ("SUCCESS".equals(result.state())) { settle(result,null,null); return detail(no,platform); }
        if ("CLOSED".equals(result.state())) { closed(no); return detail(no,platform); }
        if ("NOT_FOUND".equals(result.state()) && order.prepayStarted().plusMinutes(1).isBefore(now())) {
            closed(no); return detail(no,platform);
        }
        if (!"NOTPAY".equals(result.state())) throw new BusinessException("支付正在处理中，请稍后核对订单");
        try { payments.close(no,order.appId(),order.mchId()); }
        catch(BusinessException e) { return sync(no,platform); } // A successful payment may race with close.
        closed(no);
        return detail(no,platform);
    }
    public void notifyPayment(Map<String,String> headers,String body) {
        var notice=payments.notification(headers,body);
        settle(notice.transaction(),notice.eventId(),hash(body));
    }
    /** No client/session is trusted here; every result is authenticated and matched to its stored order. */
    public void reconcilePending() {
        if (!payments.settings().requestReady()) return;
        var pending=jdbc.query("SELECT * FROM membership_purchase_order WHERE status='PENDING' ORDER BY created_at LIMIT 10",this::map);
        for (Order order:pending) try {
            if (order.prepayStarted()==null) {
                if (!order.expires().isAfter(now())) closeUnstarted(order.no());
                continue;
            }
            var result=payments.query(order.no(),order.appId(),order.mchId());
            validateReference(order,result);
            if ("SUCCESS".equals(result.state())) settle(result,null,null);
            else if ("CLOSED".equals(result.state())) closed(order.no());
            else if (!order.expires().plusMinutes(1).isAfter(now())) {
                if ("NOT_FOUND".equals(result.state())) closed(order.no());
                else if ("NOTPAY".equals(result.state())) {
                    payments.close(order.no(),order.appId(),order.mchId()); closed(order.no());
                }
            }
        } catch(RuntimeException ignored) {
            // Leave pending; callbacks, the next reconciliation, or an explicit query can recover it.
        }
    }

    private void settle(WechatPayGateway.Transaction payment,String eventId,String hash) {
        tx.executeWithoutResult(t->{
            policies.current(true); // Same ordering as signing consumption and renewal.
            Order order=load(payment.orderNo());
            validateReference(order,payment);
            if (!"SUCCESS".equals(payment.state()) || payment.amountFen()!=order.amount()
                    || !"CNY".equals(payment.currency()) || !"JSAPI".equals(payment.tradeType())
                    || !order.openid().equals(payment.openid()) || payment.transactionId().isBlank()
                    || payment.transactionId().length()>64 || payment.successTime()==null)
                throw new IllegalArgumentException("支付金额、付款人或状态与订单不一致");
            if (eventId!=null) {
                var previous=jdbc.query("SELECT order_no FROM membership_payment_event WHERE event_id=?",
                        (rs,row)->rs.getString(1),eventId);
                if (!previous.isEmpty() && !previous.get(0).equals(order.no()))
                    throw new IllegalArgumentException("支付事件与订单关联不一致");
                jdbc.update("""
                        INSERT IGNORE INTO membership_payment_event(event_id,order_no,content_hash,received_at) VALUES (?,?,?,?)
                        """,eventId,order.no(),hash,now());
            }
            if ("PAID".equals(order.status())) {
                if (!payment.transactionId().equals(order.transaction())) throw new IllegalArgumentException("支付交易号不一致");
                return;
            }
            LocalDateTime at=now(),start=null,end=null,quotaEnd=at.plusDays(order.quotaDays());
            if ("VIP".equals(order.type())) {
                var prior=jdbc.query("SELECT expires_at FROM membership_paid_vip WHERE subject_key=?",
                        (rs,row)->rs.getTimestamp(1).toLocalDateTime(),order.subject());
                start=prior.isEmpty() || prior.get(0).isBefore(at)?at:prior.get(0);
                end=start.plusDays(order.vipDays());
                jdbc.update("""
                        INSERT INTO membership_paid_vip(subject_key,company_id,expires_at) VALUES (?,?,?)
                        ON DUPLICATE KEY UPDATE company_id=VALUES(company_id),expires_at=VALUES(expires_at)
                        """,order.subject(),order.companyId(),end);
            }
            jdbc.update("""
                    INSERT INTO membership_quota_pool(scope_key,subject_key,company_id,total_amount,expires_at)
                    VALUES (?,?,?,?,?)
                    ""","PAID:"+order.no(),order.subject(),order.companyId(),order.quota(),quotaEnd);
            jdbc.update("""
                    UPDATE membership_purchase_order SET status='PAID',transaction_id=?,paid_at=?,granted_at=?,
                      vip_start_at=?,vip_end_at=?,quota_end_at=? WHERE order_no=?
                    """,payment.transactionId(),LocalDateTime.ofInstant(payment.successTime(),MembershipPolicy.BEIJING),
                    at,start,end,quotaEnd,order.no());
        });
    }
    private void closed(String no) {
        tx.executeWithoutResult(t->{
            policies.current(true);
            jdbc.update("UPDATE membership_purchase_order SET status='CLOSED' WHERE order_no=? AND status='PENDING'",no);
        });
    }
    private boolean closeUnstarted(String no) {
        return tx.execute(t->{
            policies.current(true);
            Order current=load(no);
            if (!"PENDING".equals(current.status())) return true;
            if (current.prepayStarted()!=null) return false;
            jdbc.update("UPDATE membership_purchase_order SET status='CLOSED' WHERE order_no=? AND status='PENDING'",no);
            return true;
        });
    }
    private String saleReason(CompanyRespDTO company,MembershipPolicyStore.Snapshot snapshot,String platform) {
        if (!snapshot.known()) return "会员策略正在同步";
        if (!manager(company.getId())) return "请由法人或管理员购买企业权益";
        if (!"VERIFIED".equals(company.getCertificationStatus())) return "请先完成企业认证";
        var blocked=snapshot.policy().blacklist().get(company.getId());
        if (blocked!=null && blocked.active(clock.instant())) return "当前企业暂停新发起签署，请先联系平台";
        var special=snapshot.policy().whitelist().get(company.getId());
        if (special!=null && special.active(clock.instant()) && "UNLIMITED".equals(special.mode()))
            return "当前企业享受免费无限签权益，无需购买";
        if (!snapshot.policy().billingEnabled() || !payments.ready()) return "购买暂未开放";
        if (!payments.platformAllowed(platform)) return "当前客户端暂未开放企业服务购买";
        return "";
    }
    private CompanyRespDTO company(boolean purchasing) {
        long id=AuthContext.requireCompanyId();
        if (!access.isActiveMember(id,AuthContext.userId())) throw new BusinessException("当前企业成员身份无效");
        if (purchasing) access.requireManager(id);
        CompanyRespDTO value=companies.selectById(id);
        if (value==null || value.getCreditCode()==null || value.getCreditCode().isBlank())
            throw new BusinessException("企业主体信息不完整");
        return value;
    }
    private boolean manager(long companyId) {
        return access.hasPermission(companyId,"member_manage") || access.hasPermission(companyId,"auth_manage");
    }
    private boolean firstVip(String subject) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM membership_paid_vip WHERE subject_key=?",Long.class,subject)==0
                && jdbc.queryForObject("SELECT COUNT(*) FROM membership_purchase_order WHERE subject_key=? AND product_type='VIP' AND status='PAID'",
                    Long.class,subject)==0;
    }
    private boolean paidVip(String subject) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM membership_paid_vip WHERE subject_key=? AND expires_at>?",
                Long.class,subject,now())>0;
    }
    private Order owned(String no,CompanyRespDTO company) {
        Order order=load(no);
        if (!subject(company).equals(order.subject())) throw new BusinessException("订单不属于当前企业");
        return order;
    }
    private Order load(String no) {
        if (no==null || !no.matches("MP[0-9a-f]{30}")) throw new BusinessException("订单编号无效");
        return jdbc.query("SELECT * FROM membership_purchase_order WHERE order_no=?",this::map,no).stream()
                .findFirst().orElseThrow(()->new BusinessException("订单不存在"));
    }
    private Order map(java.sql.ResultSet rs,int row) throws java.sql.SQLException {
        return new Order(rs.getString("order_no"),rs.getLong("company_id"),rs.getString("subject_key"),rs.getLong("buyer_id"),
                rs.getString("product_id"),rs.getString("product_name"),rs.getString("product_type"),rs.getInt("amount_fen"),
                rs.getBoolean("first_offer"),rs.getInt("vip_days"),rs.getLong("sign_quota"),rs.getInt("quota_days"),
                rs.getString("app_id"),rs.getString("mch_id"),rs.getString("payer_openid"),rs.getString("status"),
                rs.getString("prepay_id"),date(rs,"prepay_started_at"),rs.getString("transaction_id"),date(rs,"created_at"),date(rs,"expires_at"),
                date(rs,"paid_at"),date(rs,"vip_start_at"),date(rs,"vip_end_at"),date(rs,"quota_end_at"));
    }
    private LocalDateTime date(java.sql.ResultSet rs,String column) throws java.sql.SQLException {
        return rs.getTimestamp(column)==null?null:rs.getTimestamp(column).toLocalDateTime();
    }
    private OrderView view(Order order,CompanyRespDTO company,String platform,MembershipPolicyStore.Snapshot snapshot) {
        boolean pending="PENDING".equals(order.status()), expired=!order.expires().isAfter(now());
        String text="PAID".equals(order.status())?"已支付，权益已到账":"CLOSED".equals(order.status())?"已关闭"
                : expired?"已超时，待核对":"待支付";
        boolean canPay=pending && !expired && order.buyer()==AuthContext.userId()
                && saleReason(company,snapshot,platform).isBlank()
                && payments.settings().appId().equals(order.appId()) && payments.settings().mchId().equals(order.mchId());
        return new OrderView(order.no(),company.getId().toString(),company.getName(),order.name(),order.type(),order.amount(),
                order.quota(),order.vipDays(),order.quotaDays(),order.first(),order.status(),text,
                display(order.created()),display(order.expires()),display(order.paid()),display(order.vipStart()),
                display(order.vipEnd()),display(order.quotaEnd()),canPay,pending && (order.prepayStarted()==null || payments.settings().requestReady()));
    }
    private void validateReference(Order order,WechatPayGateway.Transaction value) {
        if (!order.no().equals(value.orderNo()) || !order.appId().equals(value.appId()) || !order.mchId().equals(value.mchId()))
            throw new IllegalArgumentException("支付交易与订单不一致");
    }
    private String subject(CompanyRespDTO company) { return company.getCreditCode().trim().toUpperCase(Locale.ROOT); }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(),MembershipPolicy.BEIJING); }
    private String display(LocalDateTime value) {
        return value==null?"":MembershipPolicy.display(value.atZone(MembershipPolicy.BEIJING).toInstant());
    }
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
}
