package cn.iocoder.yudao.module.franchise.api;

import cn.iocoder.yudao.module.platform.org.PostSnapshot;

import java.math.BigDecimal;

/**
 * B 端方案回写端口（B2 板块 A / A7）：franchise-design 把 AI 结果回写 fr_customer_scheme。
 * franchise-crm 以 {@code CustomerSchemeAdapter} 实现；读写均先执行 CRM 对象权限
 * （{@code CustomerAccess.canRead/canWrite}），错误统一 CUSTOMER_NOT_ACCESSIBLE。
 *
 * <p>幂等唯一责任方=franchise-design 层（advisory lock + replay/record，事件 payload=规范化请求、
 * 首次结果写入 aggregateId/revision）；本端口转发 {@code CustomerService.saveSchemeInternal}
 * （客户行锁内重读+版本分配，不走其自带幂等分支——该分支同键同内容重试哈希不匹配，Codex R1 已认定缺陷）。
 */
public interface CustomerSchemePort {

    /**
     * 回写方案版本（只追加）。kind ∈ PLAN/FACADE/BUDGET（BUDGET 要求宽/深/层/面积均>0，
     * 由 CRM normalize 校验）；designProjectId 经内部命令写入桥接列，旧 HTTP 接口不可指定。
     *
     * @return 首次结果（schemeId/version）；重放由上层收据承担
     */
    SchemeSaved saveScheme(PostSnapshot post, long customerId, SchemeSave command);

    /** 回写前置（§五校验顺序①）：CRM 对象权限校验（canWrite），先于 design 侧引用校验执行 */
    void requireWritable(PostSnapshot post, long customerId);

    /** 桥接查询（R10 带customerId）：先执行 CRM 可读校验（canRead），再返回去重后的关联项目集合 */
    java.util.List<Long> listDesignProjectIds(PostSnapshot post, long customerId);

    record SchemeSave(String kind, String title, String payload,
                      BigDecimal width, BigDecimal depth, Integer floors, BigDecimal area,
                      Long totalCents, Long designProjectId) {
    }

    record SchemeSaved(long schemeId, int version) {
    }
}
