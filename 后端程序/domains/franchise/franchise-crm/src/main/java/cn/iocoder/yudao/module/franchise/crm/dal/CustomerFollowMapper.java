package cn.iocoder.yudao.module.franchise.crm.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface CustomerFollowMapper extends BaseMapperX<CustomerFollowDO> {

    default List<CustomerFollowDO> selectByCustomer(Long customerId) {
        return selectList(new LambdaQueryWrapperX<CustomerFollowDO>()
                .eq(CustomerFollowDO::getCustomerId, customerId)
                .orderByAsc(CustomerFollowDO::getCreateTime).orderByAsc(CustomerFollowDO::getId));
    }

}
