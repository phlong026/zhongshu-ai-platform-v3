package cn.iocoder.yudao.module.franchise.finance.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface FinTxnNoMapper extends BaseMapperX<FinTxnNoDO> {
    default FinTxnNoDO find(Long companyId, String txnNo) {
        return selectOne(new LambdaQueryWrapperX<FinTxnNoDO>()
                .eq(FinTxnNoDO::getCompanyId, companyId).eq(FinTxnNoDO::getTxnNo, txnNo));
    }
}