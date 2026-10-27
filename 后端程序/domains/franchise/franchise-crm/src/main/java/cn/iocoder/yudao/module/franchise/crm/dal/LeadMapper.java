package cn.iocoder.yudao.module.franchise.crm.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface LeadMapper extends BaseMapperX<LeadDO> {

    default List<LeadDO> selectByCustomer(Long customerId) {
        return selectList(new LambdaQueryWrapperX<LeadDO>().eq(LeadDO::getCustomerId, customerId)
                .orderByAsc(LeadDO::getCreateTime).orderByAsc(LeadDO::getId));
    }

}
