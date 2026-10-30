package cn.iocoder.yudao.module.platform.workflow;

/**
 * 发起审批的内容。
 *
 * @param orgId            审批所属组织（通常为事项归属组织），只有该组织的 approverRoleCode 岗位能审批
 * @param subjectVersion   事项版本，仅作记录
 * @param signature        业务域对金额、版本、依据等关键内容计算的摘要（建议 SHA-256），不同即需重新审批
 * @param approverRoleCode 有权审批的岗位编码，如 boss
 */
public record ApprovalRequest(String domain, long orgId, String subjectType, long subjectId, Long subjectVersion,
                              String signature, Long amountCents, String approverRoleCode) {
}
