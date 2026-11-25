package cn.iocoder.yudao.module.franchise.engineering;

import cn.iocoder.yudao.module.franchise.api.EngineeringProjectContextPort;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngProjectMapper;
import org.springframework.stereotype.Component;

/** 只投影持久化工程归属，避免资产授权反向依赖工程命令服务。 */
@Component
public class EngineeringProjectContextAdapter implements EngineeringProjectContextPort {
    private final FrEngProjectMapper projects;

    public EngineeringProjectContextAdapter(FrEngProjectMapper projects) {
        this.projects = projects;
    }

    @Override
    public ProjectContext findBySourceCommission(long companyId, long commissionId) {
        var rows = projects.selectByCommission(companyId, commissionId);
        if (rows.isEmpty()) return null;
        if (rows.size() != 1) throw new IllegalStateException("同一委托关联多个工程，需核查工程归属");
        var project = rows.get(0);
        return new ProjectContext(project.getId(), project.getCompanyId(), project.getSourceCommissionId(),
                project.getManagerUserId(), project.getAssignedEngineerUserId());
    }

    @Override
    public java.util.Set<Long> sourceCommissionsForActor(long companyId,long userId) {
        return projects.selectList(new cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX<cn.iocoder.yudao.module.franchise.engineering.dal.FrEngProjectDO>()
                .eq(cn.iocoder.yudao.module.franchise.engineering.dal.FrEngProjectDO::getCompanyId,companyId)
                .isNotNull(cn.iocoder.yudao.module.franchise.engineering.dal.FrEngProjectDO::getSourceCommissionId)
                .and(q -> q.eq(cn.iocoder.yudao.module.franchise.engineering.dal.FrEngProjectDO::getManagerUserId,userId)
                        .or().eq(cn.iocoder.yudao.module.franchise.engineering.dal.FrEngProjectDO::getAssignedEngineerUserId,userId)))
                .stream().map(cn.iocoder.yudao.module.franchise.engineering.dal.FrEngProjectDO::getSourceCommissionId)
                .collect(java.util.stream.Collectors.toSet());
    }
}
