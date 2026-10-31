package cn.iocoder.yudao.module.franchise.finance.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface FinPayableMapper extends BaseMapperX<FinPayableDO> {
    default FinPayableDO lockById(Long id) { return selectOne(new LambdaQueryWrapperX<FinPayableDO>().eq(FinPayableDO::getId, id).last("FOR UPDATE")); }

    default List<FinPayableDO> selectByCompany(Long companyId, String status) {
        return selectList(new LambdaQueryWrapperX<FinPayableDO>()
                .eq(FinPayableDO::getCompanyId, companyId)
                .eqIfPresent(FinPayableDO::getStatus, status)
                .orderByDesc(FinPayableDO::getCreateTime).orderByDesc(FinPayableDO::getId));
    }
}