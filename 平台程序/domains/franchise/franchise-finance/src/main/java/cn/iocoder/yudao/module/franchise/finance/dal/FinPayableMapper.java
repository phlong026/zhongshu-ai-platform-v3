package cn.iocoder.yudao.module.franchise.finance.dal;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
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

    /**
     * Web 财务核对页只读分页。FinPayableDO 无到期日/业务日期字段——本查询不提供任何日期或到期日参数，
     * 也不承诺导出的「到期日」列（§三.1 / §八.2 实证）。
     */
    default PageResult<FinPayableDO> selectPage(PageParam page, Long companyId, String status,
                                                Long projectId, String keyword) {
        LambdaQueryWrapperX<FinPayableDO> query = new LambdaQueryWrapperX<FinPayableDO>()
                .eq(FinPayableDO::getCompanyId, companyId)
                .eqIfPresent(FinPayableDO::getStatus, status)
                .eqIfPresent(FinPayableDO::getProjectId, projectId);
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            query.and(q -> q.like(FinPayableDO::getRecipient, kw).or().like(FinPayableDO::getStage, kw)
                    .or().like(FinPayableDO::getAgreementRef, kw));
        }
        return selectPage(page, query.orderByDesc(FinPayableDO::getCreateTime).orderByDesc(FinPayableDO::getId));
    }
}