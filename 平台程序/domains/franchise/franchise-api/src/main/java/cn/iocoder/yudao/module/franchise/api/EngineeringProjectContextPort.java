package cn.iocoder.yudao.module.franchise.api;

/** Mapper-only engineering scope projection; do not depend on engineering command services. */
public interface EngineeringProjectContextPort {
    record ProjectContext(long projectId, long companyId, long sourceCommissionId,
                          Long managerUserId, Long assignedEngineerUserId) { }
    /** Returns null before engineering creation. */
    ProjectContext findBySourceCommission(long companyId, long commissionId);
    /** Current engineering ownership, used when historical packet recipients have since been transferred. */
    default java.util.Set<Long> sourceCommissionsForActor(long companyId,long userId) { return java.util.Set.of(); }
}
