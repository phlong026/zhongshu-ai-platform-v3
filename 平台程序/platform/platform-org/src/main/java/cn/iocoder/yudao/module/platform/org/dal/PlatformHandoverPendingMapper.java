package cn.iocoder.yudao.module.platform.org.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import org.apache.ibatis.annotations.Mapper;

import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface PlatformHandoverPendingMapper extends BaseMapperX<PlatformHandoverPendingDO> {

    default List<PlatformHandoverPendingDO> selectOpenByOrg(long orgId) {
        return selectList(new LambdaQueryWrapperX<PlatformHandoverPendingDO>()
                .eq(PlatformHandoverPendingDO::getOrgId, orgId)
                .eq(PlatformHandoverPendingDO::getStatus, PlatformHandoverPendingDO.STATUS_OPEN)
                .orderByAsc(PlatformHandoverPendingDO::getCreateTime));
    }

    default List<PlatformHandoverPendingDO> selectOpenByUser(long orgId, long fromUserId) {
        return selectList(new LambdaQueryWrapperX<PlatformHandoverPendingDO>()
                .eq(PlatformHandoverPendingDO::getOrgId, orgId)
                .eq(PlatformHandoverPendingDO::getFromUserId, fromUserId)
                .eq(PlatformHandoverPendingDO::getStatus, PlatformHandoverPendingDO.STATUS_OPEN));
    }

    /** 补交接/正常交接完成后核销该离职人在本组织<b>本轮已转移域</b>的 OPEN 行（未接入域不动，防误核销） */
    default int markHandedOver(long orgId, long fromUserId, Long toUserId, java.util.Collection<String> domains) {
        if (domains == null || domains.isEmpty()) {
            return 0; // 无 Port 的裁剪部署：空集合会生成 IN () 非法 SQL（Codex R3 P2）
        }
        return update(new PlatformHandoverPendingDO()
                        .setStatus(PlatformHandoverPendingDO.STATUS_HANDED_OVER)
                        .setToUserId(toUserId)
                        .setUpdateTime(OffsetDateTime.now())
                        .setHandedOverAt(OffsetDateTime.now()),
                new LambdaQueryWrapperX<PlatformHandoverPendingDO>()
                        .eq(PlatformHandoverPendingDO::getOrgId, orgId)
                        .eq(PlatformHandoverPendingDO::getFromUserId, fromUserId)
                        .in(PlatformHandoverPendingDO::getDomain, domains)
                        .eq(PlatformHandoverPendingDO::getStatus, PlatformHandoverPendingDO.STATUS_OPEN));
    }
}
