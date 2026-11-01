package cn.iocoder.yudao.module.franchise.crm;

import cn.iocoder.yudao.module.franchise.api.CustomerSchemePort;
import cn.iocoder.yudao.module.franchise.crm.dal.CustomerDO;
import cn.iocoder.yudao.module.franchise.crm.dal.CustomerSchemeMapper;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.crm.enums.FranchiseCrmErrorCodes.CUSTOMER_NOT_FOUND;

/**
 * B 端方案回写适配器（B2 板块 A / P-3）：franchise-crm 实现 CustomerSchemePort。
 *
 * <p>读写均先执行 CRM 对象权限（{@code CustomerAccess.canRead/canWrite}，透传 PostSnapshot）——
 * 可见与不存在统一 CUSTOMER_NOT_FOUND（跟随 CRM 既有语义，不区分暴露，Codex R1），
 * 无权办理报 CUSTOMER_FORBIDDEN；franchise-design 层统一换译 1_112_000_002。
 */
@Slf4j
@Component
public class CustomerSchemeAdapter implements CustomerSchemePort {

    private final CustomerService customerService;
    private final CustomerSchemeMapper schemeMapper;

    public CustomerSchemeAdapter(CustomerService customerService, CustomerSchemeMapper schemeMapper) {
        this.customerService = customerService;
        this.schemeMapper = schemeMapper;
    }

    @Override
    public SchemeSaved saveScheme(PostSnapshot post, long customerId, SchemeSave command) {
        var saved = customerService.saveSchemeInternal(post, customerId, new CustomerService.SchemeSaveCommand(
                command.kind(), command.title(), command.payload(),
                command.width(), command.depth(), command.floors(), command.area(),
                command.totalCents(), command.designProjectId()));
        log.info("[saveScheme][customer={} kind={} version={} designProject={}]",
                customerId, saved.getKind(), saved.getVersion(), saved.getDesignProjectId());
        return new SchemeSaved(saved.getId(), saved.getVersion());
    }

    @Override
    public void requireWritable(PostSnapshot post, long customerId) {
        customerService.requireWritable(post, customerId);
    }

    @Override
    public void requireAccessible(PostSnapshot post, long customerId) {
        // 交易流对象校验（板块 B）：存在+同组织，不收窄岗位（boss/finance 均过——SalesAccess 契约）
        CustomerDO customer = customerService.requireAccessible(post, customerId);
        log.info("[requireAccessible][customer={} org={}]", customer.getId(), post.orgId());
    }

    @Override
    public CustomerSchemePort.CustomerBrief getCustomerBrief(PostSnapshot post, long customerId) {
        CustomerDO customer = customerService.requireAccessible(post, customerId);
        return new CustomerSchemePort.CustomerBrief(customer.getId(), customer.getName(), customer.getPhone());
    }

    @Override
    public CustomerSchemePort.SchemeSnapshot getSchemeSnapshot(PostSnapshot post, long schemeId) {
        var scheme = schemeMapper.selectById(schemeId);
        if (scheme == null) {
            return null;
        }
        requireSameOrg(post, scheme);
        return toSnapshot(scheme);
    }

    @Override
    public CustomerSchemePort.SchemeSnapshot promoteToFormal(PostSnapshot post, long schemeId, String idempotencyKey) {
        return toSnapshot(customerService.promoteSchemeToFormal(post, schemeId, idempotencyKey));
    }

    private void requireSameOrg(PostSnapshot post, cn.iocoder.yudao.module.franchise.crm.dal.CustomerSchemeDO scheme) {
        // 组织一致性复核（交易流授权=调用方已过 sales 门）
        if (scheme.getCompanyId() != post.orgId()) {
            throw exception(CUSTOMER_NOT_FOUND);
        }
    }

    private CustomerSchemePort.SchemeSnapshot toSnapshot(cn.iocoder.yudao.module.franchise.crm.dal.CustomerSchemeDO scheme) {
        return new CustomerSchemePort.SchemeSnapshot(scheme.getId(), scheme.getCompanyId(),
                scheme.getCustomerId(), scheme.getKind(), scheme.getVersion(),
                scheme.getStage() == null ? "reference" : scheme.getStage());
    }

    @Override
    public List<Long> listDesignProjectIds(PostSnapshot post, long customerId) {
        // 先可读校验（防同组织业务员探测他人客户关联项目，Codex R2）；不可见与不存在统一 CUSTOMER_NOT_FOUND
        customerService.requireReadable(post, customerId);
        return schemeMapper.selectDesignProjectIds(customerId);
    }
}
