package cn.iocoder.yudao.module.franchise.content.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface FrContentDraftMapper extends BaseMapperX<FrContentDraftDO> {

    /** 一任务一件（AC9）：命中既有作品则返回，不重复建 */
    default FrContentDraftDO selectByTask(Long taskId) {
        List<FrContentDraftDO> rows = selectList(new LambdaQueryWrapperX<FrContentDraftDO>()
                .eq(FrContentDraftDO::getTaskId, taskId)
                .eq(FrContentDraftDO::getDeleted, false)
                .last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

}
