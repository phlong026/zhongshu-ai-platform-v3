package cn.iocoder.yudao.module.franchise.content.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface FrContentTaskMapper extends BaseMapperX<FrContentTaskDO> {

    /** 同素材共享转写缓存（AC4）：找同组织同 kind 已成功的同 sha256 任务 */
    default FrContentTaskDO selectSucceededBySha(Long companyId, String kind, String inputSha256) {
        List<FrContentTaskDO> rows = selectList(new LambdaQueryWrapperX<FrContentTaskDO>()
                .eq(FrContentTaskDO::getCompanyId, companyId)
                .eq(FrContentTaskDO::getKind, kind)
                .eq(FrContentTaskDO::getInputSha256, inputSha256)
                .eq(FrContentTaskDO::getStatus, "SUCCEEDED")
                .last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

}
