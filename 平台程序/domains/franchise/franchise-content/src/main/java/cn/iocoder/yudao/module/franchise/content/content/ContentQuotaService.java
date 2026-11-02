package cn.iocoder.yudao.module.franchise.content.content;

import cn.iocoder.yudao.module.franchise.content.content.ContentBillingCatalog.Subject;
import cn.iocoder.yudao.module.franchise.content.provider.ContentProviderProperties;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import static cn.iocoder.yudao.module.franchise.content.enums.FranchiseContentErrorCodes.AI_QUOTA_EXCEEDED;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;

/**
 * 组织日配额（D-1.5-8）：(org_id, service, day) 计数，超限 1_114_000_002（等待语义）。
 * 上限取 zhongshu.content.quota.<科目> 配置，缺省用目录默认值。
 * 单语句 CAS：条件 upsert（count+units <= limit 才写入）无行返回即超限——并发安全无竞态。
 */
@Component
public class ContentQuotaService {

    private final JdbcTemplate jdbcTemplate;
    private final ContentProviderProperties properties;

    public ContentQuotaService(JdbcTemplate jdbcTemplate, ContentProviderProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    public int limitOf(Subject subject) {
        return properties.getQuota().getOrDefault(subject.key(), subject.defaultDailyQuota());
    }

    /** 当日已用（管理/展示用）。 */
    public int usedToday(long orgId, String subjectKey) {
        Integer used = jdbcTemplate.queryForObject(
                "SELECT count FROM fr_content_ai_usage WHERE org_id = ? AND service = ? AND day = CURRENT_DATE",
                Integer.class, orgId, subjectKey);
        return used == null ? 0 : used;
    }

    /** 预扣 units：超限抛 1_114_000_002；返回预扣后当日用量。付费外呼前调用（事务外）。
     *  首插与冲突更新同受 limit 约束（评审十三 P2-2）：首笔 units>limit 直接插入会穿透上限并锁死当日科目。 */
    public int reserve(long orgId, String subjectKey, int units) {
        if (units <= 0) {
            units = 1;
        }
        Subject subject = ContentBillingCatalog.subject(subjectKey);
        int limit = limitOf(subject);
        var updated = jdbcTemplate.queryForList(
                "INSERT INTO fr_content_ai_usage (id, org_id, service, day, count) "
                        + "SELECT ?, ?, ?, CURRENT_DATE, ? WHERE ? <= ? "
                        + "ON CONFLICT (org_id, service, day) DO UPDATE SET count = fr_content_ai_usage.count + EXCLUDED.count "
                        + "WHERE fr_content_ai_usage.count + EXCLUDED.count <= ? "
                        + "RETURNING count",
                Long.class, IdWorker.getId(), orgId, subjectKey, units, units, limit, limit);
        if (updated.isEmpty()) {
            throw exception(AI_QUOTA_EXCEEDED);
        }
        return updated.get(0).intValue();
    }

    /** 释放（未成单回退）：仅当当日仍有余量时递减，不落负。 */
    public void release(long orgId, String subjectKey, int units) {
        if (units <= 0) {
            return;
        }
        jdbcTemplate.update(
                "UPDATE fr_content_ai_usage SET count = GREATEST(0, count - ?) "
                        + "WHERE org_id = ? AND service = ? AND day = CURRENT_DATE",
                units, orgId, subjectKey);
    }

}
