package cn.iocoder.yudao.module.franchise.crm.controller.admin.vo;

import cn.iocoder.yudao.module.franchise.crm.LeadService.CaptureResult;
import cn.iocoder.yudao.module.franchise.crm.dal.CustomerDO;
import cn.iocoder.yudao.module.franchise.crm.dal.CustomerFollowDO;
import cn.iocoder.yudao.module.franchise.crm.dal.CustomerSchemeDO;
import cn.iocoder.yudao.module.franchise.crm.dal.LeadDO;
import cn.iocoder.yudao.module.franchise.crm.CustomerService.Detail;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 客资与客户的对外响应：不含 company_id / team_id / origin_lead_id 等内部归属字段
 * （组织隔离在 SQL 层，不需要也不应把组织编号暴露给前端）。
 */
public interface CrmRespVos {

    record Lead(long id, String name, String phone, String need, String source, String status,
                String conversion, Long customerId, long registrarUserId, String note,
                long revision, OffsetDateTime createTime) {
        public static Lead of(LeadDO lead) {
            return new Lead(lead.getId(), lead.getName(), lead.getPhone(), lead.getNeed(), lead.getSource(),
                    lead.getStatus(), lead.getConversion(), lead.getCustomerId(), lead.getRegistrarUserId(),
                    lead.getNote(), lead.getRevision(), lead.getCreateTime());
        }
    }

    record Customer(long id, String name, String phone, String wechat, String source, String province,
                    String city, String summary, String status, long ownerUserId, LocalDate nextVisitDate,
                    OffsetDateTime lastFollowAt, long revision, OffsetDateTime createTime) {
        public static Customer of(CustomerDO c) {
            return new Customer(c.getId(), c.getName(), c.getPhone(), c.getWechat(), c.getSource(),
                    c.getProvince(), c.getCity(), c.getSummary(), c.getStatus(), c.getOwnerUserId(),
                    c.getNextVisitDate(), c.getLastFollowAt(), c.getRevision(), c.getCreateTime());
        }
    }

    record Follow(long id, long customerId, String note, LocalDate nextVisitDate, long authorUserId,
                  OffsetDateTime createTime) {
        public static Follow of(CustomerFollowDO f) {
            return new Follow(f.getId(), f.getCustomerId(), f.getNote(), f.getNextVisitDate(),
                    f.getAuthorUserId(), f.getCreateTime());
        }
    }

    record Scheme(long id, long customerId, String kind, int version, String title, String payload,
                  java.math.BigDecimal width, java.math.BigDecimal depth, Integer floors,
                  java.math.BigDecimal area, Long totalCents, long authorUserId, OffsetDateTime createTime,
                  String stage) {
        public static Scheme of(CustomerSchemeDO s) {
            String stage = s.getStage();
            if (stage == null || stage.isBlank()) {
                stage = "reference";
            }
            return new Scheme(s.getId(), s.getCustomerId(), s.getKind(), s.getVersion(), s.getTitle(),
                    s.getPayload(), s.getWidth(), s.getDepth(), s.getFloors(), s.getArea(),
                    s.getTotalCents(), s.getAuthorUserId(), s.getCreateTime(), stage);
        }
    }

    record CustomerDetail(Customer customer, List<Follow> follows, List<Scheme> schemes) {
        public static CustomerDetail of(Detail detail) {
            return new CustomerDetail(Customer.of(detail.customer()),
                    detail.follows().stream().map(Follow::of).toList(),
                    detail.schemes().stream().map(Scheme::of).toList());
        }
    }

    record Capture(long leadId, Long customerId, String result) {
        public static Capture of(CaptureResult r) {
            return new Capture(r.leadId(), r.customerId(), r.outcome().name());
        }
    }

}
