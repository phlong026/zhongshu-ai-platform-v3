package cn.iocoder.yudao.module.platform.org;

/**
 * M01-T04：离职交接 Port——由业务域实现，扫描并批量转移离职员工的在途对象。
 *
 * <p>接口定义在 platform-org（编排方），实现散布在各业务域（如 franchise-crm 的客户域），
 * 经 Spring {@code ObjectProvider<DepartureHandoverPort>} 注入编排服务——方向与
 * {@link WalletSessionRevocation} 相同（依赖方向 franchise → platform，避免反向成环）。
 * 实现不存在时（组装裁剪/域未接入）编排服务在交接清单中如实报告「未接入（待分配）」，
 * 不伪造已交接。
 *
 * <p>实现合同：
 * <ul>
 *   <li>{@code scanInFlight} 只读，不改数据；统计离职人在该组织名下仍需承接的个人责任对象数；
 *       业务已归档但仍限制本人办理收款等后续事项的对象也须纳入；</li>
 *   <li>{@code validateReceiver} 只读校验接收人可承接<b>本域业务</b>（无对应岗位/岗位线不符抛业务
 *       异常）——编排器校验通用资格（在职/本组织/未停用），域级资格由本方法把守（Codex R1 P1-1：
 *       只有财务岗的人不得接收客户）；编排器在<b>任何转移写入前</b>对各已接入域统一调用；</li>
 *   <li>{@code bulkTransfer} 必须幂等（重复执行=0 行），只改责任字段并推进行版本（revision+1，
 *       Codex R1 P2-2：堵旧版本并发写绕过），历史经办记录（提交人、跟进流水、已确认业绩）一律
 *       保留不改写；在调用方事务内执行（与撤权同事务提交/回滚）；接收人域级资格已由
 *       {@code validateReceiver} 前置校验。</li>
 * </ul>
 */
public interface DepartureHandoverPort {

    /** 域标识（交接清单键，如 franchise-crm） */
    String domain();

    /** 域显示名（交接清单展示，如「客户」） */
    String label();

    /** 本域只有组织共享事项，无个人独占办理责任；与未接入、未知扫描明确区分。 */
    default boolean organizationShared() {
        return false;
    }

    /** 只读扫描：离职员工在该组织仍需承接的个人责任对象数 */
    long scanInFlight(long fromUserId, long orgId);

    /** 只读校验：接收人在该组织能否承接本域业务；不合格抛业务异常（先于任何转移写入） */
    void validateReceiver(long toUserId, long orgId);

    /** 批量转移离职员工在该组织的全部在途对象给接手人，返回转移数（幂等：重复执行=0） */
    int bulkTransfer(long fromUserId, long toUserId, long orgId, long operatorUserId);
}
