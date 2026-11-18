package cn.iocoder.yudao.module.platform.wallet;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.jdbc.core.JdbcTemplate;

/** The caller holds the order lock. Ledger, balance and the payment transition share that transaction. */
final class CompanyRechargeLedger {
    static void change(JdbcTemplate jdbc, RechargePaymentService.OrderSnapshot order,
                       String type, long reference, long delta) {
        WalletScope.requireTransaction();
        int updated = jdbc.update("""
                UPDATE org_point_account SET balance=balance+?,version=version+1,update_time=now()
                WHERE org_id=? AND balance+? >= 0
                """,delta,order.orgId(),delta);
        if (updated!=1) throw WalletException.invalidState();
        jdbc.update("""
                INSERT INTO org_point_ledger(id,org_id,biz_key,operation_time,type,actor_user_id,actor_post_id,
                    available_delta,ref_type,ref_id,note)
                VALUES(?,?,?,now(),?,?,?,?, 'RECHARGE_ORDER',?,?)
                """,IdWorker.getId(),order.orgId(),type+":"+reference,type,order.actorId(),order.postId(),
                delta,order.id(),"公司积分充值订单 "+order.orderNo());
    }
}
