package cn.iocoder.yudao.module.franchise.crm.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface FollowUpMapper extends BaseMapperX<FollowUpDO> {

    default List<FollowUpDO> selectByCustomer(Long customerId) {
        return selectList(new LambdaQueryWrapperX<FollowUpDO>().eq(FollowUpDO::getCustomerId, customerId)
                .orderByAsc(FollowUpDO::getCreateTime).orderByAsc(FollowUpDO::getId));
    }

}
