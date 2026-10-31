package cn.iocoder.yudao.module.franchise.finance.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface FinBankFlowMapper extends BaseMapperX<FinBankFlowDO> {
    default List<FinBankFlowDO> selectByCompanyAndStatus(Long companyId, String status) {
        return selectList(new LambdaQueryWrapperX<FinBankFlowDO>()
                .eq(FinBankFlowDO::getCompanyId, companyId)
                .eqIfPresent(FinBankFlowDO::getStatus, status)
                .orderByDesc(FinBankFlowDO::getCreateTime));
    }
}