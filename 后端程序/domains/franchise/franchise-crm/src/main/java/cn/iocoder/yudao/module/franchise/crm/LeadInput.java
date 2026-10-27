package cn.iocoder.yudao.module.franchise.crm;

/** 客资登记内容；source 为空时记为「直接来访」 */
public record LeadInput(String name, String phone, String need, String source) {
}
