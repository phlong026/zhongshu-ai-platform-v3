package cn.iocoder.yudao.module.franchise.crm;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.franchise.crm.dal.CustomerDO;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;

import java.time.LocalDate;

/**
 * 客资、客户与跟进。actor 为当前工作身份（控制器传 CurrentPost.require()）。
 */
public interface CustomerService {

    /**
     * 登记客资：同门店同手机号已有客户时关联原客户（负责人不变），否则新建本人负责的客户。
     */
    LeadCaptureResult captureLead(PostSnapshot actor, LeadInput input);

    /** 记录跟进；expectedRevision 为客户当前版本，返回跟进编号 */
    long addFollowUp(PostSnapshot actor, long customerId, long expectedRevision, String note, LocalDate nextVisitDate);

    /** 客户详情（含客资与跟进）；不存在或无权查看抛 CUSTOMER_NOT_FOUND */
    CustomerDetail getCustomer(PostSnapshot actor, long customerId);

    /** 当前身份可见的客户列表 */
    PageResult<CustomerDO> pageCustomers(PostSnapshot actor, CustomerQuery query);

    /** 读取客户并校验当前身份可以办理（供本域其他模块使用，如提交设计委托） */
    CustomerDO requireHandleable(PostSnapshot actor, long customerId);

}
