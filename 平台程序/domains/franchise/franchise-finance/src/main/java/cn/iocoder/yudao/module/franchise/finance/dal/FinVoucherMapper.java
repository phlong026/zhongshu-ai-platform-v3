package cn.iocoder.yudao.module.franchise.finance.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface FinVoucherMapper extends BaseMapperX<FinFileDO> {
    default List<FinFileDO> selectByCompany(Long companyId) {
        return selectList(new LambdaQueryWrapperX<FinFileDO>()
                .eq(FinFileDO::getCompanyId, companyId).orderByDesc(FinFileDO::getCreateTime));
    }
}