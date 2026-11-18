package cn.iocoder.yudao.module.franchise.finance.dal;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface FinPaymentMapper extends BaseMapperX<FinPaymentDO> {
    default List<FinPaymentDO> selectByCompany(Long companyId, String status) {
        return selectList(new LambdaQueryWrapperX<FinPaymentDO>()
                .eq(FinPaymentDO::getCompanyId, companyId)
                .eqIfPresent(FinPaymentDO::getStatus, status)
                .orderByDesc(FinPaymentDO::getCreateTime).orderByDesc(FinPaymentDO::getId));
    }

    default List<FinPaymentDO> selectActiveByPayable(Long payableId) {
        return selectList(new LambdaQueryWrapperX<FinPaymentDO>()
                .eq(FinPaymentDO::getPayableId, payableId).eq(FinPaymentDO::getStatus, "已核实"));
    }

    /** 先按编号锁定状态重算要访问的应付单，再锁付款、退回流水。与报销、员工归还持锁方向一致。 */
    @Select("""
            SELECT q.id FROM fr_fin_payable q
             WHERE q.company_id=#{org} AND NOT q.deleted
               AND (q.id=(SELECT p.payable_id FROM fr_fin_payment p WHERE p.id=#{payment} AND p.company_id=#{org})
                    OR (q.source_type='reimbursement' AND q.source_ref=CAST(#{payment} AS text)))
             ORDER BY q.id FOR UPDATE
            """)
    List<Long> lockSettlementPayables(@Param("org") long org,
                                      @Param("payment") long payment);

    /** 行锁读取：支出退回登记与付款作废/并发退回互斥（M08-T08/R1 P2） */
    default FinPaymentDO lockById(Long id) { return selectOne(new LambdaQueryWrapperX<FinPaymentDO>().eq(FinPaymentDO::getId, id).last("FOR UPDATE")); }

    /** Web 财务核对页只读分页：期间按付款日期 payDate，组织范围强制收敛。 */
    default PageResult<FinPaymentDO> selectPage(PageParam page, Long companyId, String status,
                                                LocalDate payDateFrom, LocalDate payDateTo,
                                                Long projectId, String keyword) {
        LambdaQueryWrapperX<FinPaymentDO> query = new LambdaQueryWrapperX<FinPaymentDO>()
                .eq(FinPaymentDO::getCompanyId, companyId)
                .eqIfPresent(FinPaymentDO::getStatus, status)
                .geIfPresent(FinPaymentDO::getPayDate, payDateFrom)
                .leIfPresent(FinPaymentDO::getPayDate, payDateTo)
                .eqIfPresent(FinPaymentDO::getProjectId, projectId);
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            query.and(q -> q.like(FinPaymentDO::getTransactionNo, kw).or().like(FinPaymentDO::getVoidReason, kw));
        }
        return selectPage(page, query.orderByDesc(FinPaymentDO::getCreateTime).orderByDesc(FinPaymentDO::getId));
    }
}