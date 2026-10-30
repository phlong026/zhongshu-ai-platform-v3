package cn.iocoder.yudao.module.platform.authz;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ScopeProbeMapper extends BaseMapperX<ScopeProbeDO> {

    default List<ScopeProbeDO> selectBatch(String batch) {
        return selectList(ScopeProbeDO::getBatch, batch);
    }

    /** 带别名与 JOIN 的手写 SQL，确认规则对别名同样生效 */
    @Select("SELECT p.* FROM authz_scope_probe p JOIN authz_scope_probe q ON q.id = p.id WHERE p.batch = #{batch}")
    List<ScopeProbeDO> selectBatchWithAlias(String batch);

}
