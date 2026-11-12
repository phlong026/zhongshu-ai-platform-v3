package cn.iocoder.yudao.module.franchise.org.wallet;

import cn.iocoder.yudao.module.infra.zhongshu.api.IdentitySessionIssuePort;
import cn.iocoder.yudao.module.platform.org.WalletSessionRevocation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

/**
 * 撤权回调实现（platform-org 接口，franchise-org 提供）：按组织解析服务账号并吊销其全部钱包会话。
 *
 * <p>实现合同（09 文档 §二-1/§四-2）：<b>无副作用</b>——按 external_key 查既存账号，缺失幂等返回，
 * 禁止建号/补挂/复活（与 {@code StoreServiceAccountPort.requireServiceAccount} 的准入语义区分，
 * 否则「先停用账号再撤岗」会抛异常、「从未开过钱包的组织撤权」会反向创建账号）。须在调用方事务内执行。
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "platform.legacy-design-bridge.enabled", havingValue = "true", matchIfMissing = true)
public class WalletSessionRevocationAdapter implements WalletSessionRevocation {

    private static final String FR_SERVICE_PREFIX = "fr-service:";

    private final JdbcTemplate jdbcTemplate;
    private final IdentitySessionIssuePort identitySessionIssuePort;

    public WalletSessionRevocationAdapter(DataSource dataSource,
                                          IdentitySessionIssuePort identitySessionIssuePort) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.identitySessionIssuePort = identitySessionIssuePort;
    }

    @Override
    public void revokeForUserOrgs(long userId, List<Long> orgIds) {
        revokeForOrgs(orgIds);
    }

    @Override
    public void revokeForOrg(long orgId) {
        revokeForOrgs(List.of(orgId));
    }

    private void revokeForOrgs(List<Long> orgIds) {
        if (orgIds == null || orgIds.isEmpty()) {
            return;
        }
        List<Long> accountIds = new ArrayList<>();
        for (Long orgId : orgIds) {
            if (orgId == null) {
                continue;
            }
            var rows = jdbcTemplate.queryForList(
                    "SELECT id FROM account WHERE external_key = ? AND deleted = FALSE",
                    String.class, FR_SERVICE_PREFIX + orgId);
            if (!rows.isEmpty() && rows.get(0) != null) {
                accountIds.add(Long.valueOf(rows.get(0)));
            }
        }
        identitySessionIssuePort.revokeForAccounts(accountIds);
    }

}
