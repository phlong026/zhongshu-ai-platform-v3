package cn.iocoder.yudao.module.platform.identity.dal;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import org.apache.ibatis.annotations.Mapper;

/**
 * 查询会经组织范围规则（OrgScopeRule）自动限定在当前工作身份所在组织。
 */
@Mapper
public interface WechatBindingEventMapper extends BaseMapperX<WechatBindingEventDO> {

    default PageResult<WechatBindingEventDO> selectPage(PageParam page, Long userId) {
        return selectPage(page, new LambdaQueryWrapperX<WechatBindingEventDO>()
                .eqIfPresent(WechatBindingEventDO::getUserId, userId)
                .orderByDesc(WechatBindingEventDO::getOccurredAt)
                .orderByDesc(WechatBindingEventDO::getId));
    }

}
