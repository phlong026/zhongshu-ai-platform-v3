package cn.iocoder.yudao.module.franchise.finance;

import cn.iocoder.yudao.module.franchise.finance.dal.FinMatchAttemptDO;
import cn.iocoder.yudao.module.franchise.finance.dal.FinMatchAttemptMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 到账匹配失败尝试留痕：REQUIRES_NEW 独立事务，不随确认主事务回滚（需求文档 §四）。
 * 必须独立 Bean——同类内部调用不经过代理会让 REQUIRES_NEW 失效。
 */
@Service
@Slf4j
public class FinAttemptRecorder {

    @Resource
    private FinMatchAttemptMapper attemptMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(long flowId, Long receiptId, String reason) {
        try {
            attemptMapper.insert(new FinMatchAttemptDO().setBankFlowId(flowId)
                    .setTriedReceiptId(receiptId).setReason(reason));
        } catch (Exception e) {
            log.warn("[record][flow={}] 匹配失败留痕写库异常（不影响主流程）", flowId, e);
        }
    }

}
