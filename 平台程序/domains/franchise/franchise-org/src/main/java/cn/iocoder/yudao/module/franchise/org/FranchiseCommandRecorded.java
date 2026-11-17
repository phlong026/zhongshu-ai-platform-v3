package cn.iocoder.yudao.module.franchise.org;

import cn.iocoder.yudao.module.platform.command.dal.CommandEventDO;

/** Published synchronously inside the business transaction, after command identity checks. */
public record FranchiseCommandRecorded(CommandEventDO event) { }
