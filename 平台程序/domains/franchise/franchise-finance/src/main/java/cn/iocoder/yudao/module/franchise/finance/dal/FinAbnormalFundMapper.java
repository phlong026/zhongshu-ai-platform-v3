package cn.iocoder.yudao.module.franchise.finance.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface FinAbnormalFundMapper extends BaseMapperX<FinAbnormalFundDO> {

    default List<FinAbnormalFundDO> selectByCompany(Long companyId, String status, boolean datePendingOnly) {
        return selectList(new LambdaQueryWrapperX<FinAbnormalFundDO>()
                .eq(FinAbnormalFundDO::getCompanyId, companyId)
                .eqIfPresent(FinAbnormalFundDO::getStatus, status)
                // 日期待核单列待归月（M08-T05 契约）：business_date IS NULL
                .isNull(datePendingOnly, FinAbnormalFundDO::getBusinessDate)
                .orderByDesc(FinAbnormalFundDO::getCreateTime).orderByDesc(FinAbnormalFundDO::getId));
    }
}
