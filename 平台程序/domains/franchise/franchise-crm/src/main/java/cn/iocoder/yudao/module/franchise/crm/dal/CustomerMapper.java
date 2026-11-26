package cn.iocoder.yudao.module.franchise.crm.dal;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.Consumer;

/** 查询均受组织范围规则约束，只返回当前工作身份所在组织的数据 */
@Mapper
public interface CustomerMapper extends BaseMapperX<CustomerDO> {

    enum FollowFilter { DUE, APPOINTMENTS, VISITED }

    default CustomerDO selectByPhone(String phone) {
        return selectOne(CustomerDO::getPhone, phone);
    }

    /** 导入用：本组织内该手机号客户（显式 company 过滤；手机号在本组织唯一）。 */
    default CustomerDO selectByPhoneInOrg(long companyId, String phone) {
        List<CustomerDO> list = selectList(new LambdaQueryWrapperX<CustomerDO>()
                .eq(CustomerDO::getCompanyId, companyId)
                .eq(CustomerDO::getPhone, phone));
        return list.isEmpty() ? null : list.get(0);
    }

    default PageResult<CustomerDO> selectPage(PageParam page, Consumer<LambdaQueryWrapperX<CustomerDO>> visibility,
                                              String keyword) {
        return selectPage(page, visibility, keyword, null, null);
    }

    /**
     * Web 客资表格：可见范围 + keyword + 状态 + 负责人组合筛选（旧 3 参方法保持兼容）。
     * 空值不参与过滤；状态只接受 REGISTERED/FOLLOWING（由控制器校验）。
     */
    default PageResult<CustomerDO> selectPage(PageParam page, Consumer<LambdaQueryWrapperX<CustomerDO>> visibility,
                                              String keyword, String status, Long ownerUserId) {
        return selectPage(page, visibility, keyword, status, ownerUserId, null);
    }

    default PageResult<CustomerDO> selectPage(PageParam page, Consumer<LambdaQueryWrapperX<CustomerDO>> visibility,
                                              String keyword, String status, Long ownerUserId, FollowFilter followFilter) {
        return selectPage(page, pageQuery(visibility, keyword, status, ownerUserId, followFilter,
                LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))));
    }

    default LambdaQueryWrapperX<CustomerDO> pageQuery(Consumer<LambdaQueryWrapperX<CustomerDO>> visibility,
            String keyword, String status, Long ownerUserId, FollowFilter followFilter, LocalDate today) {
        LambdaQueryWrapperX<CustomerDO> query = new LambdaQueryWrapperX<>();
        visibility.accept(query);
        if (keyword != null && !keyword.isBlank()) {
            String term = keyword.trim();
            query.and(q -> q.like(CustomerDO::getName, term).or().like(CustomerDO::getPhone, term)
                    .or().like(CustomerDO::getProvince, term).or().like(CustomerDO::getCity, term)
                    .or().like(CustomerDO::getDistrict, term).or().like(CustomerDO::getTown, term)
                    .or().like(CustomerDO::getVillage, term).or().like(CustomerDO::getAddressDetail, term));
        }
        query.eqIfPresent(CustomerDO::getStatus, status);
        query.eqIfPresent(CustomerDO::getOwnerUserId, ownerUserId);
        if (followFilter != null) {
            switch (followFilter) {
                // 今日待跟进：进行中或暂缓的客户，尚未跟进过，或约定的下次跟进日期已到（含今天）。已流失不再提醒。
                case DUE -> query.apply("NOT " + LOST).and(q -> q.isNull(CustomerDO::getLastFollowAt)
                        .or().le(CustomerDO::getNextVisitDate, today)).orderByAsc(CustomerDO::getNextVisitDate);
                case APPOINTMENTS -> query.apply("NOT " + LOST).gt(CustomerDO::getNextVisitDate, today).orderByAsc(CustomerDO::getNextVisitDate);
                // 已到店：第 2 步“店面讲解”有记录；升级前明确登记为“实际接待”的普通跟进同样算到店。
                // 两个“到过店”的条件必须括在一起，否则 OR 后半段会绕过可见范围和“未流失”。
                case VISITED -> query.apply("NOT " + LOST).and(q -> q
                        .exists("SELECT 1 FROM fr_customer_follow f WHERE f.customer_id = fr_customer.id"
                                + " AND f.company_id = fr_customer.company_id AND f.contact_kind = 'VISIT'")
                        .or().apply(VISITED_STEP));
            }
        }
        query.orderByDesc(CustomerDO::getUpdateTime).orderByDesc(CustomerDO::getId);
        return query;
    }

    /** 客户已标记流失（八步流程状态）。 */
    String LOST = "EXISTS (SELECT 1 FROM fr_customer_workflow w WHERE w.customer_id = fr_customer.id"
            + " AND w.company_id = fr_customer.company_id AND w.status = 'LOST')";
    /** 第 2 步“店面讲解”已有记录，即客户到过店。 */
    String VISITED_STEP = "EXISTS (SELECT 1 FROM fr_customer_workflow_step s JOIN fr_customer_workflow w ON w.id = s.workflow_id"
            + " AND w.company_id = s.company_id WHERE w.customer_id = fr_customer.id AND w.company_id = fr_customer.company_id"
            + " AND s.step_code = 'INTRODUCTION')";
    /** 流程已暂停（暂缓）：到再联系日期才回到待跟进。 */
    String PAUSED = "EXISTS (SELECT 1 FROM fr_customer_workflow w WHERE w.customer_id = fr_customer.id"
            + " AND w.company_id = fr_customer.company_id AND w.status = 'PAUSED')";

    /**
     * 当前可见客户的跟进汇总，口径与 followFilter 同源：今日待跟进 = 未流失且（尚未跟进，或约定的下次跟进日期已到）；
     * 预约跟进 = 未流失且日期晚于今天；跟进中 = 跟进过且未流失、未暂缓。可见范围沿用同一个 visibility 条件。
     */
    @org.apache.ibatis.annotations.Select("SELECT count(*) AS \"total\","
            + " count(*) FILTER (WHERE NOT " + LOST + " AND (last_follow_at IS NULL OR next_visit_date <= #{today})) AS \"due\","
            + " count(*) FILTER (WHERE NOT " + LOST + " AND next_visit_date > #{today}) AS \"appointments\","
            + " count(*) FILTER (WHERE NOT " + LOST + " AND next_visit_date < #{today}) AS \"overdue\","
            + " count(*) FILTER (WHERE last_follow_at IS NOT NULL AND NOT " + LOST + " AND NOT " + PAUSED + ") AS \"following\","
            + " count(*) FILTER (WHERE " + LOST + ") AS \"lost\","
            + " count(*) FILTER (WHERE " + PAUSED + ") AS \"paused\""
            + " FROM fr_customer ${ew.customSqlSegment}")
    java.util.Map<String, Object> selectFollowSummaryMap(@org.apache.ibatis.annotations.Param("ew")
                                                          com.baomidou.mybatisplus.core.conditions.Wrapper<CustomerDO> wrapper,
                                                          @org.apache.ibatis.annotations.Param("today") java.time.LocalDate today);

    default cn.iocoder.yudao.module.franchise.crm.CustomerService.FollowSummary selectFollowSummary(Consumer<LambdaQueryWrapperX<CustomerDO>> visibility, java.time.LocalDate today) {
        LambdaQueryWrapperX<CustomerDO> query = new LambdaQueryWrapperX<>();
        visibility.accept(query);
        var row = selectFollowSummaryMap(query, today);
        if (row == null) return new cn.iocoder.yudao.module.franchise.crm.CustomerService.FollowSummary(0, 0, 0, 0, 0, 0, 0);
        java.util.function.Function<String, Long> count = key -> row.get(key) instanceof Number value ? value.longValue() : 0L;
        return new cn.iocoder.yudao.module.franchise.crm.CustomerService.FollowSummary(count.apply("total"), count.apply("due"), count.apply("appointments"),
                count.apply("overdue"), count.apply("following"), count.apply("lost"), count.apply("paused"));
    }

    /** 当前可见范围内出现过的负责人（去重），供 Web 负责人筛选下拉。 */
    default List<Long> selectOwnerIds(Consumer<LambdaQueryWrapperX<CustomerDO>> visibility) {
        LambdaQueryWrapperX<CustomerDO> query = new LambdaQueryWrapperX<>();
        visibility.accept(query);
        query.select(CustomerDO::getOwnerUserId).groupBy(CustomerDO::getOwnerUserId);
        return selectList(query).stream().map(CustomerDO::getOwnerUserId).distinct().toList();
    }

    /** 行锁读取（B2 板块 A / P-9：AI 回写的并发版本分配要求锁内重读客户） */
    default CustomerDO lockById(Long id) {
        return selectOne(new LambdaQueryWrapperX<CustomerDO>().eq(CustomerDO::getId, id).last("FOR UPDATE"));
    }

    /** 约定的下次跟进日期已到（含今天）的客户；已流失的不再提醒。 */
    default List<CustomerDO> selectDueVisits(Consumer<LambdaQueryWrapperX<CustomerDO>> visibility, LocalDate today) {
        LambdaQueryWrapperX<CustomerDO> query = new LambdaQueryWrapperX<>();
        visibility.accept(query);
        return selectList(query.apply("NOT " + LOST).le(CustomerDO::getNextVisitDate, today).orderByAsc(CustomerDO::getNextVisitDate));
    }

    /**
     * 超期未跟进，用于提醒业务负责人：新客户登记满两天仍没有任何跟进记录；
     * 或最近一次跟进已超过 15 天，且没有约定下次跟进日期（约了日期的由到期待办提醒）。
     */
    default List<CustomerDO> selectStale(Consumer<LambdaQueryWrapperX<CustomerDO>> visibility, LocalDate today) {
        LambdaQueryWrapperX<CustomerDO> query = new LambdaQueryWrapperX<>();
        visibility.accept(query);
        var zone = java.time.ZoneId.of("Asia/Shanghai");
        var registeredBefore = today.minusDays(2).atStartOfDay(zone).toOffsetDateTime();
        var followedBefore = today.minusDays(15).atStartOfDay(zone).toOffsetDateTime();
        query.apply("NOT " + LOST).and(q -> q
                .and(n -> n.isNull(CustomerDO::getLastFollowAt).le(CustomerDO::getCreateTime, registeredBefore))
                .or(f -> f.le(CustomerDO::getLastFollowAt, followedBefore).isNull(CustomerDO::getNextVisitDate)));
        return selectList(query.orderByAsc(CustomerDO::getUpdateTime).orderByAsc(CustomerDO::getId));
    }

    /** 已流失、且流失后又有客资登记进来（再次咨询）的客户，等负责人确认是否恢复跟进。 */
    default List<CustomerDO> selectReconsulted(Consumer<LambdaQueryWrapperX<CustomerDO>> visibility) {
        LambdaQueryWrapperX<CustomerDO> query = new LambdaQueryWrapperX<>();
        visibility.accept(query);
        return selectList(query.apply("EXISTS (SELECT 1 FROM fr_customer_workflow w WHERE w.customer_id = fr_customer.id"
                + " AND w.company_id = fr_customer.company_id AND w.status = 'LOST' AND w.reconsulted_at IS NOT NULL)")
                .orderByAsc(CustomerDO::getId));
    }

    default List<CustomerDO> selectVisible(Consumer<LambdaQueryWrapperX<CustomerDO>> visibility) {
        LambdaQueryWrapperX<CustomerDO> query = new LambdaQueryWrapperX<>();
        visibility.accept(query);
        return selectList(query);
    }

    /**
     * 按版本更新：只改 changes 中 set 的列，并把版本号加一；影响 0 行表示已被他人改动。
     * 用显式 set 而不是实体，才能把列改为空（如清空预约回访日期）。
     */
    default int updateByRevision(Long id, long expectedRevision, Consumer<LambdaUpdateWrapper<CustomerDO>> changes) {
        LambdaUpdateWrapper<CustomerDO> update = new LambdaUpdateWrapper<CustomerDO>()
                .eq(CustomerDO::getId, id)
                .eq(CustomerDO::getRevision, expectedRevision)
                .set(CustomerDO::getRevision, expectedRevision + 1)
                .set(CustomerDO::getUpdateTime, OffsetDateTime.now());
        changes.accept(update);
        return update(null, update);
    }

}
