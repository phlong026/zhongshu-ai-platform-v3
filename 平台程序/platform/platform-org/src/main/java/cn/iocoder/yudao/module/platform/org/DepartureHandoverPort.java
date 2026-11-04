package cn.iocoder.yudao.module.platform.org;

/** M01-T04：离职交接 Port——由业务域实现，批量转移离职员工在途对象。 */
public interface DepartureHandoverPort {

    /** 批量转移离职员工在指定组织的全部在途客户给接手人 */
    int bulkTransferCustomers(long fromUserId, long toUserId, long orgId);

    /** 批量转移离职员工的全部在途委托给接手人 */
    int bulkTransferCommissions(long fromUserId, long toUserId, long orgId);
}
