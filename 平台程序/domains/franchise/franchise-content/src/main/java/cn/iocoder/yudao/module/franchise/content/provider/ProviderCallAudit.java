package cn.iocoder.yudao.module.franchise.content.provider;

/** 外呼审计上下文：provider_call 行的 company_id/task_id（服务层传入；无任务语境时 NONE）。 */
public record ProviderCallAudit(Long companyId, Long taskId) {

    public static final ProviderCallAudit NONE = new ProviderCallAudit(null, null);

}
