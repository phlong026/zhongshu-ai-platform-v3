package cn.iocoder.yudao.module.franchise.api;

import cn.iocoder.yudao.module.platform.org.PostSnapshot;

/**
 * 统一建房合同查询端口（M06-T03）：franchise-sales 实现，franchise-commission 移交链经此解析
 * 客户当前活跃的统一建房合同（同 C 唯一活跃合同守卫由 sales 侧保证）。设计委托款独立首尾约定，
 * 不与建房收入合并——本端口只回答「该客户有没有关联的统一合同」，不做资金语义。
 */
public interface SaleContractQueryPort {

    /** 客户在指定组织的活跃（SIGNED）统一建房合同编号；无则返回 null（移交档案 contractId 兼容为空） */
    Long findActiveSignedId(PostSnapshot post, long customerId);
}
