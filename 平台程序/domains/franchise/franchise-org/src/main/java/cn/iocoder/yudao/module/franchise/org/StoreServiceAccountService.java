package cn.iocoder.yudao.module.franchise.org;

import cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort;
import cn.iocoder.yudao.module.infra.zhongshu.api.AccountProvisionPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;

/**
 * 组织服务账号（B2 板块 A / A9）：每组织一个 identity C 端占位账号。
 *
 * <p>解析唯一路径=identity {@code account.external_key}（"fr-service:{orgId}"）——
 * 无第二份绑定映射（Codex R6 删除 fr_org_service_account 双映射）；每次解析都执行状态校验，
 * 停用账号抛 {@link ServiceAccountInactiveException}（调用方换译 1_112_000_004），禁止惰性复活/换建。
 */
@Slf4j
@Service
public class StoreServiceAccountService implements StoreServiceAccountPort {

    private final AccountProvisionPort accountProvision;
    private final JdbcTemplate jdbcTemplate;

    public StoreServiceAccountService(AccountProvisionPort accountProvision, DataSource dataSource) {
        this.accountProvision = accountProvision;
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @Override
    public long requireServiceAccount(long orgId) {
        String externalKey = "fr-service:" + orgId;
        var ensured = accountProvision.ensureActiveAccount(externalKey, nicknameOf(orgId));
        if (!ensured.active()) {
            throw new ServiceAccountInactiveException(orgId);
        }
        return ensured.accountId();
    }

    /** 昵称={组织名}·服务账号；组织名缺失时退回纯标识（管理端辨识以昵称优先，D22） */
    private String nicknameOf(long orgId) {
        var names = jdbcTemplate.queryForList(
                "SELECT d.name FROM pf_org_unit u JOIN system_dept d ON d.id = u.dept_id AND d.deleted = 0 "
                        + "WHERE u.org_id = ? LIMIT 1", String.class, orgId);
        String orgName = names.isEmpty() ? null : names.get(0);
        return (orgName == null ? "组织#" + orgId : orgName) + "·服务账号";
    }
}
