package cn.iocoder.yudao.module.franchise.content.provider;

/** 口播文案改写端口（D-1.5-2）：DeepSeek deepseek-chat；服务层负责 rules 离线兜底与 prompt 快照落库。 */
public interface ScriptRewritePort {

    Result rewrite(RewriteCall call);

    /** personaPrompt=人设快照渲染后的提示段（可空）；requirement=本次补充要求（可空）。 */
    record RewriteCall(String sourceText, String requirement, String personaPrompt, ProviderCallAudit audit) {
    }

    record Result(String rewrittenText, String model) {
    }

}
