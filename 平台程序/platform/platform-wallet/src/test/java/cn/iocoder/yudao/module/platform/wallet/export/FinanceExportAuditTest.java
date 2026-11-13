package cn.iocoder.yudao.module.platform.wallet.export;

import cn.iocoder.yudao.framework.common.biz.system.logger.OperateLogCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.logger.dto.OperateLogCreateReqDTO;
import cn.iocoder.yudao.module.platform.test.PlatformPgIntegrationTest;
import jakarta.validation.Valid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.mock.web.MockHttpServletRequest;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * P0 批3：导出审计写入（§8.1 L351「导出动作记审计 actor/筛选条件/行数」）。
 *
 * <p>Codex P1① 补测：走真实 {@code @Validated} 方法校验代理（不 mock 校验链），
 * 断言 userIp/userAgent 非空时导出成功且审计落库——当前生产把两字段设空会被
 * Bean Validation 的 @NotEmpty 拒绝，导出必失败。
 *
 * <p>Codex P1③ 补测：明细字段含内嵌 CR/LF 时，审计「行数」必须等于写出循环里
 * 真实累计的明细记录数（不能用 String.lines() 从 CSV 文本反推）。
 */
class FinanceExportAuditTest extends PlatformPgIntegrationTest {

    @Autowired DataSource dataSource;
    private JdbcTemplate jdbc;
    private final OperateLogCommonApi audit = mock(OperateLogCommonApi.class);

    /** 真实 @Validated 代理：方法参数上的 @Valid 触发 HibernateValidator 校验。 */
    @Validated
    static class ValidatingAudit implements OperateLogCommonApi {
        private final AtomicReference<OperateLogCreateReqDTO> captured;

        ValidatingAudit(AtomicReference<OperateLogCreateReqDTO> captured) {
            this.captured = captured;
        }

        @Override
        public void createOperateLog(@Valid OperateLogCreateReqDTO dto) {
            captured.set(dto); // 校验通过才执行 → 等价于「审计落库」
        }
    }

    private static OperateLogCommonApi validatingAudit(AtomicReference<OperateLogCreateReqDTO> captured) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.registerBean(LocalValidatorFactoryBean.class);
        ctx.register(MethodValidationPostProcessor.class);
        ctx.registerBean(ValidatingAudit.class, () -> new ValidatingAudit(captured));
        ctx.refresh();
        return ctx.getBean(OperateLogCommonApi.class);
    }

    @BeforeEach
    void schema() {
        RequestContextHolder.resetRequestAttributes();
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE IF NOT EXISTS fr_fin_receipt (id BIGINT, company_id BIGINT, project_id BIGINT, "
                + "customer_id BIGINT, source_type VARCHAR(16), source_ref VARCHAR(128), name VARCHAR(128), "
                + "amount_cents BIGINT, status VARCHAR(16), received_date DATE, registered_at TIMESTAMPTZ, "
                + "registrar_user_id BIGINT, method VARCHAR(16), transaction_no VARCHAR(64), voucher_file_id BIGINT, "
                + "revision_version INT, void_reason VARCHAR(255), void_by_user_id BIGINT, create_time TIMESTAMPTZ)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS fr_fin_abnormal_fund (id BIGINT, company_id BIGINT, "
                + "fund_direction VARCHAR(32), amount_cents BIGINT, business_date DATE, status VARCHAR(16), "
                + "create_time TIMESTAMPTZ)");
        jdbc.update("DELETE FROM fr_fin_receipt WHERE company_id = ?", 91000L);
    }

    private byte[] exportWithAudit(OperateLogCommonApi api, String table, Map<String, String> filters,
                                   long orgId, long actor) throws Exception {
        Method m = FinanceExportService.class.getDeclaredMethod("exportForTest", JdbcTemplate.class, String.class,
                Map.class, long.class, long.class, OperateLogCommonApi.class);
        m.setAccessible(true);
        return (byte[]) m.invoke(null, jdbc, table, filters, orgId, actor, api);
    }

    private byte[] exportWithAudit(String table, Map<String, String> filters, long orgId, long actor) throws Exception {
        return exportWithAudit(audit, table, filters, orgId, actor);
    }

    private void insertReceipt(long id, String name, String txn) {
        jdbc.update("INSERT INTO fr_fin_receipt (id,company_id,project_id,source_type,source_ref,name,amount_cents,"
                        + "status,received_date,registered_at,registrar_user_id,method,transaction_no,revision_version,"
                        + "void_reason,create_time) VALUES (?,?,?,?,?,?,?,?,?,now(),?,?,?,?,?,now())",
                id, 91000L, 101L, "manual", "REF-A", name, 10000, "已登记",
                java.time.LocalDate.of(2026, 11, 5), 77L, "bank", txn, 1, null);
    }

    @Test
    void exportWritesAuditWithActorFiltersAndRowCount() throws Exception {
        insertReceipt(9_100_001L, "审计样本", "TX-AUDIT");

        exportWithAudit("receipts", Map.of("status", "已登记", "projectId", "101"), 91000L, 77L);

        ArgumentCaptor<OperateLogCreateReqDTO> captor = ArgumentCaptor.forClass(OperateLogCreateReqDTO.class);
        verify(audit).createOperateLog(captor.capture());
        OperateLogCreateReqDTO req = captor.getValue();
        assertThat(req.getUserId()).isEqualTo(77L); // actor
        assertThat(req.getUserType()).isEqualTo(2); // ADMIN
        assertThat(req.getSubType()).isEqualTo("receipts");
        assertThat(req.getBizId()).isEqualTo(91000L);
        assertThat(req.getAction()).contains("status=已登记", "projectId=101", "行数="); // 筛选条件 + 行数
        assertThat(req.getExtra()).contains("\"table\":\"receipts\"", "\"orgId\":91000", "\"rows\":");
        assertThat(req.getRequestMethod()).isEqualTo("POST");
        assertThat(req.getRequestUrl()).isEqualTo("/franchise/finance/exports");
    }

    // ===== Codex P1①：真实 @Validated 校验代理，导出必须成功且审计落库 =====

    @Test
    void realValidatedAuditProxyAcceptsExportWithRealRequestContext() throws Exception {
        insertReceipt(9_100_002L, "校验代理样本", "TX-VALID-1");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.20.30.40");
        request.addHeader("User-Agent", "finance-export-web/1.0");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        AtomicReference<OperateLogCreateReqDTO> captured = new AtomicReference<>();
        OperateLogCommonApi realProxy = validatingAudit(captured);
        // 真实校验代理：userIp/userAgent 为空会抛 ConstraintViolationException → 导出失败（红期）
        byte[] csv = exportWithAudit(realProxy, "receipts", Map.of(), 91000L, 77L);

        assertThat(csv[0]).isEqualTo((byte) 0xEF); // 导出成功（BOM）
        assertThat(captured.get()).isNotNull(); // 审计落库
        assertThat(captured.get().getUserIp()).isEqualTo("10.20.30.40");
        assertThat(captured.get().getUserAgent()).isEqualTo("finance-export-web/1.0");
    }

    @Test
    void realValidatedAuditProxyAcceptsExportWithoutRequestContext() throws Exception {
        // 无请求上下文（如定时/内部调用）：必须用受控非空占位，不能留空触发 @NotEmpty
        insertReceipt(9_100_003L, "无请求上下文样本", "TX-VALID-2");
        AtomicReference<OperateLogCreateReqDTO> captured = new AtomicReference<>();
        OperateLogCommonApi realProxy = validatingAudit(captured);

        byte[] csv = exportWithAudit(realProxy, "receipts", Map.of(), 91000L, 77L);

        assertThat(csv[0]).isEqualTo((byte) 0xEF);
        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().getUserIp()).isNotBlank();
        assertThat(captured.get().getUserAgent()).isNotBlank();
        assertThat(captured.get().getUserIp()).isEqualTo("unknown");
        assertThat(captured.get().getUserAgent()).isEqualTo("finance-export");
    }

    // ===== Codex P1③：字段内嵌 CR/LF 时审计行数必须准确 =====

    @Test
    void auditRowCountIgnoresEmbeddedNewlinesInsideCsvFields() throws Exception {
        // name 含 \r\n：csvRow 会把它包在引号字段内；String.lines() 会把它当两行多计。
        // 用 mock audit 隔离校验链，让本用例只精确暴露「行数」这一处缺陷。
        insertReceipt(9_100_004L, "换行\r\n样本", "TX-NEWLINE");
        org.mockito.Mockito.clearInvocations(audit);

        exportWithAudit("receipts", Map.of(), 91000L, 77L);

        ArgumentCaptor<OperateLogCreateReqDTO> captor = ArgumentCaptor.forClass(OperateLogCreateReqDTO.class);
        verify(audit).createOperateLog(captor.capture());
        OperateLogCreateReqDTO req = captor.getValue();
        // 明细只有 1 行，审计 action/extra 里的 rows 必须是 1，不能因字段内换行算成 2
        assertThat(req.getAction()).contains("行数=1");
        assertThat(req.getExtra()).contains("\"rows\":1");
        assertThat(req.getAction()).doesNotContain("行数=2");
    }
}
