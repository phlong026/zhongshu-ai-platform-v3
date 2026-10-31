package cn.iocoder.yudao.module.franchise.finance.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface FinReceiptMapper extends BaseMapperX<FinReceiptDO> {
    default FinReceiptDO lockById(Long id) { return selectOne(new LambdaQueryWrapperX<FinReceiptDO>().eq(FinReceiptDO::getId, id).last("FOR UPDATE")); }

    default List<FinReceiptDO> selectByCompany(Long companyId, String status) {
        return selectList(new LambdaQueryWrapperX<FinReceiptDO>()
                .eq(FinReceiptDO::getCompanyId, companyId)
                .eqIfPresent(FinReceiptDO::getStatus, status)
                .orderByDesc(FinReceiptDO::getCreateTime).orderByDesc(FinReceiptDO::getId));
    }
}