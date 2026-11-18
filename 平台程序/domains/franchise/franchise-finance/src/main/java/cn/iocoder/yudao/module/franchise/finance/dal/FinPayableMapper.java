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
        return withEngineeringNames(selectList(new LambdaQueryWrapperX<FinPayableDO>()
                .eq(FinPayableDO::getCompanyId, companyId)
                .eqIfPresent(FinPayableDO::getStatus, status)
                .orderByDesc(FinPayableDO::getCreateTime).orderByDesc(FinPayableDO::getId)), companyId);
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
        PageResult<FinPayableDO> result = selectPage(page, query.orderByDesc(FinPayableDO::getCreateTime).orderByDesc(FinPayableDO::getId));
        withEngineeringNames(result.getList(), companyId);
        return result;
    }

    @org.apache.ibatis.annotations.Select("""
        SELECT payable_id::text AS id, name, 'engineering_expense' AS stage
        FROM fr_eng_expense WHERE company_id=#{companyId} AND payable_id IS NOT NULL
        UNION ALL
        SELECT s.payable_id::text AS id, m.name, 'engineering' AS stage
        FROM fr_eng_supply s JOIN fr_eng_material m ON m.id=s.material_id AND m.company_id=s.company_id
        WHERE s.company_id=#{companyId} AND s.payable_id IS NOT NULL
        """)
    List<java.util.Map<String,Object>> engineeringNames(@org.apache.ibatis.annotations.Param("companyId") Long companyId);

    default List<FinPayableDO> withEngineeringNames(List<FinPayableDO> rows, Long companyId) {
        // 普通财务/设计应付不依赖工程表，也不应触发无关的工程名称扫描。
        if (rows.stream().noneMatch(row -> "engineering".equals(row.getStage())
                || "engineering_expense".equals(row.getStage()))) return rows;
        java.util.Map<String,java.util.Map<String,Object>> names = new java.util.HashMap<>();
        engineeringNames(companyId).forEach(row -> names.put(String.valueOf(row.get("id")), row));
        rows.forEach(row -> {
            var source = names.get(String.valueOf(row.getId()));
            if (source != null) row.setName(String.valueOf(source.get("name"))).setStage(String.valueOf(source.get("stage")));
        });
        return rows;
    }
}
