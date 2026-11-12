package cn.iocoder.yudao.module.identity.account;

import cn.iocoder.yudao.module.infra.zhongshu.api.AccountStatePort;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.sql.DataSource;

@Component
// 登记保留（G2e合同v1 §0，c31已批，前任遗留行正名）：capability 启用场景存在第二个
// AccountStatePort 候选（CapabilityServiceAccountGuard，经 DesignCapabilityConfiguration @Bean 产出，
// 见 CapabilityAccountGuardWiringTest 第54-56行），@Primary 使缺省解析保持本 consumer 适配器。
// 注意本行是全限定注解形式，按 "import + @Primary" 惯用 grep 会漏检。
@org.springframework.context.annotation.Primary
public class JdbcAccountStateAdapter implements AccountStatePort {
    private final JdbcTemplate jdbc;
    public JdbcAccountStateAdapter(DataSource dataSource) { jdbc = new JdbcTemplate(dataSource); }
    @Override public void requireActiveForWrite(long accountId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("account write guard requires transaction");
        var rows = jdbc.queryForList("SELECT status FROM account WHERE id=? AND deleted=FALSE FOR SHARE", String.class, accountId);
        if (rows.isEmpty() || !"ACTIVE".equals(rows.get(0))) throw new ServiceException(403, "账号已停用或关闭");
    }
}
