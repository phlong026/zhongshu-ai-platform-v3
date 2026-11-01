package cn.iocoder.yudao.module.franchise.crm;

import cn.iocoder.yudao.module.franchise.api.CustomerSchemePort;
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
    public List<Long> listDesignProjectIds(PostSnapshot post, long customerId) {
        // 先可读校验（防同组织业务员探测他人客户关联项目，Codex R2）；不可见与不存在统一 CUSTOMER_NOT_FOUND
        customerService.requireReadable(post, customerId);
        return schemeMapper.selectDesignProjectIds(customerId);
    }
}
