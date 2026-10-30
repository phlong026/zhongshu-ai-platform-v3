package cn.iocoder.yudao.module.platform.identity.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.time.OffsetDateTime;
import java.util.List;

import static cn.iocoder.yudao.module.platform.identity.dal.WechatBindingDO.*;

@Mapper
public interface WechatBindingMapper extends BaseMapperX<WechatBindingDO> {

    default WechatBindingDO selectActive(String appid, String openid) {
        return selectOne(new LambdaQueryWrapperX<WechatBindingDO>()
                .eq(WechatBindingDO::getAppid, appid)
                .eq(WechatBindingDO::getOpenid, openid)
                .eq(WechatBindingDO::getStatus, STATUS_ACTIVE));
    }

    default WechatBindingDO selectActiveByUser(String appid, Long userId) {
        return selectOne(new LambdaQueryWrapperX<WechatBindingDO>()
                .eq(WechatBindingDO::getAppid, appid)
                .eq(WechatBindingDO::getUserId, userId)
                .eq(WechatBindingDO::getStatus, STATUS_ACTIVE));
    }

    default List<WechatBindingDO> selectActiveByUserInAllApps(Long userId) {
        return selectList(new LambdaQueryWrapperX<WechatBindingDO>()
                .eq(WechatBindingDO::getUserId, userId)
                .eq(WechatBindingDO::getStatus, STATUS_ACTIVE));
    }

    /** 仅作废仍有效的绑定，返回影响行数 */
    default int revoke(Long id, String reason) {
        return update(new WechatBindingDO().setStatus(STATUS_REVOKED).setRevokeReason(reason).setRevokedAt(OffsetDateTime.now()),
                new LambdaUpdateWrapper<WechatBindingDO>()
                        .eq(WechatBindingDO::getId, id)
                        .eq(WechatBindingDO::getStatus, STATUS_ACTIVE));
    }

}
