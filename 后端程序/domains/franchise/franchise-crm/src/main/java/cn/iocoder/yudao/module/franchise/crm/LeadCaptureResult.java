package cn.iocoder.yudao.module.franchise.crm;

/**
 * @param linkedExisting      true 表示同门店已有该手机号客户，本次客资关联到原客户，负责人不变
 * @param customerOwnerUserId 客户负责人
 */
public record LeadCaptureResult(long leadId, long customerId, boolean linkedExisting, long customerOwnerUserId) {
}
