package cn.iocoder.yudao.module.franchise.api;

import java.util.Map;

/** 总部先验证真实 HQ 身份，再显式传入租户/公司读取；此端口无写操作或任职模拟。 */
public interface PayrollReadPort {
    Map<String,Object> rulesAuthorized(long tenantId,long companyId,String family,int pageNo,int pageSize);
    Map<String,Object> recordsAuthorized(long tenantId,long companyId,String month,String family,int pageNo,int pageSize);
    Map<String,Object> recordAuthorized(long tenantId,long companyId,long recordId);
    Map<String,Object> historyAuthorized(long tenantId,long companyId,long recordId,int pageNo,int pageSize);
}
