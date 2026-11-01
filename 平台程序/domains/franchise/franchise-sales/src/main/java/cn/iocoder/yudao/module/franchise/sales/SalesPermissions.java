package cn.iocoder.yudao.module.franchise.sales;

/** 业务交易流权限码（B2 板块 B / B10）：query/write/collect 三码分离，854 矩阵授权。 */
public final class SalesPermissions {

    public static final String QUERY = "franchise:sale:query";
    public static final String WRITE = "franchise:sale:write";
    public static final String COLLECT = "franchise:sale:collect";

    private SalesPermissions() {
    }
}
