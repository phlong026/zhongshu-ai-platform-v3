package cn.iocoder.yudao.module.franchise.test;

import cn.iocoder.yudao.module.franchise.api.BusinessReceiptPort;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.atomic.AtomicLong;

/**
 * BusinessReceiptPort 测试桩（M06-T04 三期总闸）：真实写 fr_fin_receipt 行（资金唯一事实在
 * 真表验证），transactionNo 同公司防重对齐 fr_fin_txn_no 语义；不走 FinReceiptService 全链
 * （sales 测试模块不引入 finance 服务依赖——凭证/修订/五项门禁由 finance 自身测试覆盖）。
 */
public class BusinessReceiptPortTestStub implements BusinessReceiptPort {

    private static final AtomicLong IDS = new AtomicLong(930_000_000_000L);

    private final JdbcTemplate jdbc;

    public BusinessReceiptPortTestStub(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public BusinessReceiptSaved registerBusinessReceipt(PostSnapshot post, BusinessReceipt cmd) {
        // R1 P2：对齐 fr_fin_txn_no 永久身份语义——trim 后经身份表唯一约束判占（软删不释放）；
        // R2：身份行 owner_id=收据 id（先生成 id 再写身份行，二者一致）
        String txn = cmd.transactionNo() == null ? null : cmd.transactionNo().trim();
        long id = IDS.incrementAndGet();
        if (txn != null && !txn.isEmpty()) {
            jdbc.update("INSERT INTO fr_fin_txn_no (id, company_id, txn_no, owner_type, owner_id, closed) "
                            + "VALUES (?, ?, ?, 'receipt', ?, FALSE) ON CONFLICT DO NOTHING",
                    IDS.incrementAndGet(), post.orgId(), txn, id);
            Integer claimed = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM fr_fin_txn_no WHERE company_id = ? AND txn_no = ? AND owner_id = ?",
                    Integer.class, post.orgId(), txn, id);
            if (claimed == null || claimed == 0) {
                throw new IllegalStateException("交易号已被永久占用（fr_fin_txn_no）: " + txn);
            }
        }
        jdbc.update("INSERT INTO fr_fin_receipt (id, company_id, customer_id, source_type, source_ref, name, "
                        + "amount_cents, status, received_date, registrar_user_id, method, transaction_no) "
                        + "VALUES (?, ?, ?, 'business', ?, ?, ?, '已登记', ?, ?, ?, ?)",
                id, post.orgId(), cmd.customerId(), cmd.sourceRef(), cmd.name(),
                cmd.amountCents(), cmd.receivedDate(), post.userId(),
                cmd.method() == null || cmd.method().isBlank() ? "bank" : cmd.method(), txn);
        return new BusinessReceiptSaved(id, false);
    }

    @Override
    public BusinessReceiptSaved replayBySourceRef(PostSnapshot post, String sourceRef) {
        Long id = jdbc.queryForObject(
                "SELECT id FROM fr_fin_receipt WHERE company_id = ? AND source_ref = ?",
                Long.class, post.orgId(), sourceRef);
        if (id == null) {
            throw new IllegalStateException("测试桩：source_ref 不存在 " + sourceRef);
        }
        return new BusinessReceiptSaved(id, true);
    }
}
