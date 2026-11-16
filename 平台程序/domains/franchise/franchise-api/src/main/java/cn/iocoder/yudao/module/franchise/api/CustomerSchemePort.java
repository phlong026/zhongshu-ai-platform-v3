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

    /** 仅供设计能力边界读取并验证后的 ITEMIZED_V1 快照；宽深可缺省、部分预算总额必须为空。 */
    default SchemeSaved saveItemizedBudgetSnapshot(PostSnapshot post,long customerId,SchemeSave command) {
        throw new UnsupportedOperationException("Verified itemized budget archive unavailable");
    }

    /** 回写前置（§五校验顺序①）：CRM 对象权限校验（canWrite），先于 design 侧引用校验执行 */
    void requireWritable(PostSnapshot post, long customerId);

    /** 桥接查询（R10 带customerId）：先执行 CRM 可读校验（canRead），再返回去重后的关联项目集合 */
    java.util.List<Long> listDesignProjectIds(PostSnapshot post, long customerId);

    /** 交易流对象校验（板块 B SalesAccess）：客户存在且 company_id==post.orgId()（不收窄岗位，boss/finance 均过） */
    void requireAccessible(PostSnapshot post, long customerId);

    /** 报价抬头快照（板块 B）：客户名/电话当时值（创建时冻结用） */
    CustomerBrief getCustomerBrief(PostSnapshot post, long customerId);

    /** 方案只读快照（板块 B 报价引用）：不存在返回 null；组织/客户一致性由调用方复核 */
    SchemeSnapshot getSchemeSnapshot(PostSnapshot post, long schemeId);

    /** 方案升级 reference→formal（板块 B R3）：crm 侧幂等（指纹={schemeId,targetStage:'formal'}）；授权=sales write 门+组织校验 */
    SchemeSnapshot promoteToFormal(PostSnapshot post, long schemeId, String idempotencyKey);

    record SchemeSnapshot(long schemeId, long companyId, long customerId, String kind, int version, String stage) {
    }

    record CustomerBrief(long customerId, String name, String phone) {
    }

    record SchemeSave(String kind, String title, String payload,
                      BigDecimal width, BigDecimal depth, Integer floors, BigDecimal area,
                      Long totalCents, Long designProjectId) {
    }

    record SchemeSaved(long schemeId, int version) {
    }
}
