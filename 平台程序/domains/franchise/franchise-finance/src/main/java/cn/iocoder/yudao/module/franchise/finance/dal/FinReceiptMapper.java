package cn.iocoder.yudao.module.franchise.finance.dal;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface FinReceiptMapper extends BaseMapperX<FinReceiptDO> {
    default FinReceiptDO lockById(Long id) { return selectOne(new LambdaQueryWrapperX<FinReceiptDO>().eq(FinReceiptDO::getId, id).last("FOR UPDATE")); }

    default List<FinReceiptDO> selectByCompany(Long companyId, String status) {
        return selectList(new LambdaQueryWrapperX<FinReceiptDO>()
                .eq(FinReceiptDO::getCompanyId, companyId)
                .eqIfPresent(FinReceiptDO::getStatus, status)
                .orderByDesc(FinReceiptDO::getCreateTime).orderByDesc(FinReceiptDO::getId));
    }

    /** Web 财务核对页只读分页：期间按业务日期 receivedDate（非登记时间），组织范围强制收敛。 */
    default PageResult<FinReceiptDO> selectPage(PageParam page, Long companyId, String status,
                                                LocalDate receivedDateFrom, LocalDate receivedDateTo,
                                                Long projectId, String method, String sourceType, String keyword) {
        LambdaQueryWrapperX<FinReceiptDO> query = new LambdaQueryWrapperX<FinReceiptDO>()
                .eq(FinReceiptDO::getCompanyId, companyId)
                .eqIfPresent(FinReceiptDO::getStatus, status)
                .geIfPresent(FinReceiptDO::getReceivedDate, receivedDateFrom)
                .leIfPresent(FinReceiptDO::getReceivedDate, receivedDateTo)
                .eqIfPresent(FinReceiptDO::getProjectId, projectId)
                .eqIfPresent(FinReceiptDO::getMethod, method)
                .eqIfPresent(FinReceiptDO::getSourceType, sourceType);
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            query.and(q -> q.like(FinReceiptDO::getName, kw).or().like(FinReceiptDO::getSourceRef, kw)
                    .or().like(FinReceiptDO::getTransactionNo, kw).or().like(FinReceiptDO::getMethod, kw));
        }
        return selectPage(page, query.orderByDesc(FinReceiptDO::getCreateTime).orderByDesc(FinReceiptDO::getId));
    }
}