package cn.iocoder.yudao.module.franchise.content.content;

import cn.iocoder.yudao.module.franchise.content.provider.ContentProviderProperties;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 内容科目当日用量统计。
 *
 * <p>公司积分池上线后，本表不再是配额、预扣或拒绝门槛；付费准入唯一依据是
 * OrgPointPort 的业务权限和公司余额。该统计可供运营展示，失败不得伪装成“日上限”。
 */
@Component
public class ContentQuotaService {

    private final JdbcTemplate jdbcTemplate;

    /** 保留旧构造器形状，避免独立 B 迁移时破坏已有装配。 */
    public ContentQuotaService(JdbcTemplate jdbcTemplate, ContentProviderProperties ignoredProperties) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 当日已用（管理/展示用）。 */
    public int usedToday(long orgId, String subjectKey) {
        Integer used = jdbcTemplate.queryForObject(
                "SELECT count FROM fr_content_ai_usage WHERE org_id = ? AND service = ? AND day = CURRENT_DATE",
                Integer.class, orgId, subjectKey);
        return used == null ? 0 : used;
    }

    /** 无上限的使用量累加；仅供统计，不在任务创建主路径中执行。 */
    public int recordUsage(long orgId, String subjectKey, int units) {
        if (units <= 0) {
            units = 1;
        }
        var updated = jdbcTemplate.queryForList(
                "INSERT INTO fr_content_ai_usage (id, org_id, service, day, count) "
                        + "VALUES (?, ?, ?, CURRENT_DATE, ?) "
                        + "ON CONFLICT (org_id, service, day) DO UPDATE SET count = fr_content_ai_usage.count + EXCLUDED.count "
                        + "RETURNING count",
                Long.class, IdWorker.getId(), orgId, subjectKey, units);
        return updated.get(0).intValue();
    }

}
