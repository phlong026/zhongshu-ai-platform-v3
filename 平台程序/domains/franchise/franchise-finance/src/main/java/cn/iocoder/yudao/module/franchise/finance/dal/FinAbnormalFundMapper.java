package cn.iocoder.yudao.module.franchise.finance.dal;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDate;
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

    /** Web 财务核对页只读分页：datePendingOnly 沿用旧端点「businessDate IS NULL」单列待归月语义。 */
    default PageResult<FinAbnormalFundDO> selectPage(PageParam page, Long companyId, String status,
                                                     boolean datePendingOnly, LocalDate businessDateFrom,
                                                     LocalDate businessDateTo, String keyword) {
        LambdaQueryWrapperX<FinAbnormalFundDO> query = new LambdaQueryWrapperX<FinAbnormalFundDO>()
                .eq(FinAbnormalFundDO::getCompanyId, companyId)
                .eqIfPresent(FinAbnormalFundDO::getStatus, status)
                .geIfPresent(FinAbnormalFundDO::getBusinessDate, businessDateFrom)
                .leIfPresent(FinAbnormalFundDO::getBusinessDate, businessDateTo);
        // isNull 为父类方法（返回类型非 LambdaQueryWrapperX），单独调用避免中断链式
        query.isNull(datePendingOnly, FinAbnormalFundDO::getBusinessDate);
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            query.and(q -> q.like(FinAbnormalFundDO::getCounterparty, kw)
                    .or().like(FinAbnormalFundDO::getStageOrCategory, kw)
                    .or().like(FinAbnormalFundDO::getTransactionNo, kw)
                    .or().like(FinAbnormalFundDO::getReason, kw));
        }
        return selectPage(page, query.orderByDesc(FinAbnormalFundDO::getCreateTime).orderByDesc(FinAbnormalFundDO::getId));
    }
}
