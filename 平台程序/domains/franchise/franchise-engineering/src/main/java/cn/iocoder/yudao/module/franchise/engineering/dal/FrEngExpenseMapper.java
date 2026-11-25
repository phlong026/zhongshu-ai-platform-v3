package cn.iocoder.yudao.module.franchise.engineering.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface FrEngExpenseMapper extends BaseMapperX<FrEngExpenseDO> {

    default FrEngExpenseDO lockByCompany(Long companyId, Long id) {
        return selectOne(new LambdaQueryWrapperX<FrEngExpenseDO>()
                .eq(FrEngExpenseDO::getCompanyId, companyId).eq(FrEngExpenseDO::getId, id).last("FOR UPDATE"));
    }

    default List<FrEngExpenseDO> selectByProject(Long companyId, Long projectId) {
        return selectList(new LambdaQueryWrapperX<FrEngExpenseDO>()
                .eq(FrEngExpenseDO::getCompanyId, companyId)
                .eq(FrEngExpenseDO::getProjectId, projectId)
                .orderByAsc(FrEngExpenseDO::getCreateTime));
    }
}
