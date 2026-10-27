package cn.iocoder.yudao.module.franchise.crm;

/** @param keyword 按称呼或手机号模糊匹配，可空 */
public record CustomerQuery(int pageNo, int pageSize, String keyword) {
}
