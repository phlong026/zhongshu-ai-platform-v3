package cn.iocoder.yudao.module.franchise.content.provider;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 外呼统一调用日志（五件套之二，A2.5）：每次 provider 外呼一行 fr_content_provider_call
 * （provider/endpoint/latency_ms/result_code/units_billed）——成本审计。
 * 记录失败只 warn 不阻断业务（审计行缺失不应导致已付费结果丢失）；付费外呼本身绝不进事务，
 * 本写入是外呼之后的独立短事务。
 */
@Component
public class ContentProviderCallRecorder {

    private static final Logger log = LoggerFactory.getLogger(ContentProviderCallRecorder.class);

    private final JdbcTemplate jdbcTemplate;

    public ContentProviderCallRecorder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void record(Long companyId, Long taskId, String provider, String endpoint,
                       long latencyMs, String resultCode, Integer unitsBilled) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO fr_content_provider_call (id, company_id, task_id, provider, endpoint, latency_ms, result_code, units_billed) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    IdWorker.getId(), companyId, taskId, provider, endpoint, (int) latencyMs, resultCode, unitsBilled);
        } catch (RuntimeException e) {
            log.warn("content provider call audit row failed provider={} endpoint={} task={} code={} latency={}ms: {}",
                    provider, endpoint, taskId, resultCode, latencyMs, e.getMessage());
        }
    }

}
