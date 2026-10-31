package cn.iocoder.yudao.module.franchise.finance;

/**
 * 财务账簿权限码（种子 franchise-system V*.852）与允许来源。
 * 控制器一律 @PreAuthorize("@post.has(...)")（只看当前岗位）；Service 内再校验对象 company 归属。
 */
public final class FinPermissions {

    public static final String QUERY = "franchise:fin:query";
    public static final String REGISTER = "franchise:fin:register";
    public static final String PAYMENT_VOID = "franchise:fin:payment-void";
    public static final String REFUND_VOID = "franchise:fin:refund-void";

    /** 手工登记允许的来源；design 走同一接口但服务端做委托门禁；intercompany 由移交事件内部创建，不开放手工登记 */
    public static final java.util.Set<String> MANUAL_SOURCE_TYPES = java.util.Set.of("manual", "design");

    private FinPermissions() {
    }

}
