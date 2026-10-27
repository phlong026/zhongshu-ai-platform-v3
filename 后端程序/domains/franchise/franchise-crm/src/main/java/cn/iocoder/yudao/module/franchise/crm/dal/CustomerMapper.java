package cn.iocoder.yudao.module.franchise.crm.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.time.OffsetDateTime;

/** 查询经组织范围规则自动限定在当前组织 */
@Mapper
public interface CustomerMapper extends BaseMapperX<CustomerDO> {

    default CustomerDO selectByPhone(Long companyId, String phone) {
        return selectOne(CustomerDO::getCompanyId, companyId, CustomerDO::getPhone, phone);
    }

    /** 乐观锁：版本一致才加一，返回影响行数 */
    default int bumpRevision(Long id, long expectedRevision) {
        return update(null, new LambdaUpdateWrapper<CustomerDO>()
                .setSql("revision = revision + 1")
                .set(CustomerDO::getUpdateTime, OffsetDateTime.now())
                .eq(CustomerDO::getId, id)
                .eq(CustomerDO::getRevision, expectedRevision));
    }

}
