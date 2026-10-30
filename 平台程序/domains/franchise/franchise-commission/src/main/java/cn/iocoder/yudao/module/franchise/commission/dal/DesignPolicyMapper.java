package cn.iocoder.yudao.module.franchise.commission.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import org.apache.ibatis.annotations.Mapper;

/** 查询均受组织范围规则约束 */
@Mapper
public interface DesignPolicyMapper extends BaseMapperX<DesignPolicyDO> {

    /** 本门店当前有效标准费用：version 最大且 active */
    default DesignPolicyDO selectActive(long companyId) {
        return selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DesignPolicyDO>()
                .eq(DesignPolicyDO::getCompanyId, companyId)
                .eq(DesignPolicyDO::getActive, true)
                .orderByDesc(DesignPolicyDO::getVersion)
                .last("LIMIT 1"));
    }

    default Integer selectMaxVersion(long companyId) {
        DesignPolicyDO latest = selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DesignPolicyDO>()
                .eq(DesignPolicyDO::getCompanyId, companyId)
                .orderByDesc(DesignPolicyDO::getVersion)
                .last("LIMIT 1"));
        return latest == null ? null : latest.getVersion();
    }

}
