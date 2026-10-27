package cn.iocoder.yudao.module.franchise.crm;

import cn.iocoder.yudao.module.franchise.crm.dal.CustomerDO;
import cn.iocoder.yudao.module.franchise.crm.dal.FollowUpDO;
import cn.iocoder.yudao.module.franchise.crm.dal.LeadDO;

import java.util.List;

public record CustomerDetail(CustomerDO customer, List<LeadDO> leads, List<FollowUpDO> followUps) {
}
