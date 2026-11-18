package cn.iocoder.yudao.module.franchise.api;

import cn.iocoder.yudao.module.platform.org.PostSnapshot;

import java.time.LocalDate;

/**
 * 业务回款登记端口（B2 板块 B / P-2）：franchise-sales 回款写通 B1 账簿的内部专用入口。
 * franchise-finance 以 BusinessReceiptAdapter 实现；sourceType='business'，**手工 REST 登记入口不放宽**。
 * 幂等：finance 侧键=biz-receipt:{sourceRef}（独立命名空间）；source_ref 唯一部分索引兜底并发；
 * 命中 source_ref 时比对完整规范化资金内容（不含 date——缺省日期首次固化为当日）。
 */
public interface BusinessReceiptPort {

    BusinessReceiptSaved registerBusinessReceipt(PostSnapshot post, BusinessReceipt cmd);

    /** PAID 重放专用：按 sourceRef 查既有 business 收据（资金内容一致性已由调用方指纹先行比对） */
    BusinessReceiptSaved replayBySourceRef(PostSnapshot post, String sourceRef);

    record BusinessReceipt(long customerId, String sourceRef, String name, long amountCents,
                           String method, String transactionNo, LocalDate receivedDate,Long voucherFileId,String accountName) {
        public BusinessReceipt(long customerId,String sourceRef,String name,long amountCents,String method,String transactionNo,LocalDate receivedDate){this(customerId,sourceRef,name,amountCents,method,transactionNo,receivedDate,null,null);}
    }

    record BusinessReceiptSaved(long receiptId, boolean replayed) {
    }
}
