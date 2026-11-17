package cn.iocoder.yudao.module.franchise.finance.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface FinTxnNoMapper extends BaseMapperX<FinTxnNoDO> {
    @org.apache.ibatis.annotations.Update("""
            UPDATE fr_fin_txn_no SET owner_id=#{newOwnerId}
            WHERE company_id=#{companyId} AND txn_no=#{txnNo}
                AND owner_type=#{ownerType} AND owner_id=#{oldOwnerId}
            """)
    int transfer(@org.apache.ibatis.annotations.Param("companyId") long companyId,
                 @org.apache.ibatis.annotations.Param("txnNo") String txnNo,
                 @org.apache.ibatis.annotations.Param("ownerType") String ownerType,
                 @org.apache.ibatis.annotations.Param("oldOwnerId") long oldOwnerId,
                 @org.apache.ibatis.annotations.Param("newOwnerId") long newOwnerId);

    default FinTxnNoDO find(Long companyId, String txnNo) {
        return selectOne(new LambdaQueryWrapperX<FinTxnNoDO>()
                .eq(FinTxnNoDO::getCompanyId, companyId).eq(FinTxnNoDO::getTxnNo, txnNo));
    }
}