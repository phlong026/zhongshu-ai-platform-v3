package cn.iocoder.yudao.module.franchise.crm.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface CustomerSchemeMapper extends BaseMapperX<CustomerSchemeDO> {

    default List<CustomerSchemeDO> selectByCustomer(Long customerId) {
        return selectList(new LambdaQueryWrapperX<CustomerSchemeDO>()
                .eq(CustomerSchemeDO::getCustomerId, customerId)
                .orderByAsc(CustomerSchemeDO::getKind).orderByAsc(CustomerSchemeDO::getVersion));
    }

    default CustomerSchemeDO selectLatest(Long customerId, String kind) {
        List<CustomerSchemeDO> rows = selectList(new LambdaQueryWrapperX<CustomerSchemeDO>()
                .eq(CustomerSchemeDO::getCustomerId, customerId)
                .eq(CustomerSchemeDO::getKind, kind)
                .orderByDesc(CustomerSchemeDO::getVersion)
                .last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 客户已回写关联的 AI 设计项目（去重，B2 板块 A / A7 桥接查询） */
    default List<Long> selectDesignProjectIds(Long customerId) {
        return selectList(new LambdaQueryWrapperX<CustomerSchemeDO>()
                .eq(CustomerSchemeDO::getCustomerId, customerId)
                .isNotNull(CustomerSchemeDO::getDesignProjectId)
                .select(CustomerSchemeDO::getDesignProjectId))
                .stream().map(CustomerSchemeDO::getDesignProjectId).distinct().toList();
    }

}
