package cn.iocoder.yudao.module.franchise.content.provider;

/**
 * 内容域 provider 外呼异常三分法（16a A2.2，对标复刻项目踩坑件）：
 * Rejection=明确拒绝/供应商明确失败（终态，不重试）；Uncertain=提交结果未知（绝不自动付费重投）；
 * Unavailable=服务未配置/凭据无效/暂不可用（1_114_000_006 中性文案）。
 * 对外文案永不出现供应商名（红线）。
 */
public class ContentProviderException extends RuntimeException {

    public ContentProviderException(String message) {
        super(message);
    }

    /** 明确拒绝：请求未被受理/供应商明确报告失败，无已付费副作用，任务直接终态 FAILED。 */
    public static class Rejection extends ContentProviderException {
        private final int httpStatus;
        private final String vendorCode;

        public Rejection(String message, int httpStatus, String vendorCode) {
            super(message);
            this.httpStatus = httpStatus;
            this.vendorCode = vendorCode;
        }

        public int httpStatus() {
            return httpStatus;
        }

        public String vendorCode() {
            return vendorCode;
        }
    }

    /** 提交不确定：网络类失败，可能已受理已扣费——任务置 SUBMISSION_UNCERTAIN，冻结计费，绝不自动重投。 */
    public static class Uncertain extends ContentProviderException {
        public Uncertain(String message) {
            super(message);
        }
    }

    /** 服务不可用：未配置凭据/凭据无效/上游暂不可用——中性文案 + 1_114_000_006。 */
    public static class Unavailable extends ContentProviderException {
        public Unavailable(String message) {
            super(message);
        }
    }

}
