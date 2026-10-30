package cn.iocoder.yudao.module.platform.command.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface CommandEventMapper extends BaseMapperX<CommandEventDO> {

    default CommandEventDO selectByIdempotencyKey(String idempotencyKey) {
        return selectOne(CommandEventDO::getIdempotencyKey, idempotencyKey);
    }

    default List<CommandEventDO> selectListByAggregate(String domain, String aggregateType, Long aggregateId) {
        return selectList(new LambdaQueryWrapperX<CommandEventDO>()
                .eq(CommandEventDO::getDomain, domain)
                .eq(CommandEventDO::getAggregateType, aggregateType)
                .eq(CommandEventDO::getAggregateId, aggregateId)
                .orderByAsc(CommandEventDO::getRevision));
    }

}
