package cn.iocoder.yudao.module.platform.identity.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.platform.identity.PlatformIdentityPermissions;
import cn.iocoder.yudao.module.platform.identity.StaffWechatBindingService;
import cn.iocoder.yudao.module.platform.identity.controller.admin.vo.WechatBindingEventPageReqVO;
import cn.iocoder.yudao.module.platform.identity.controller.admin.vo.WechatBindingEventRespVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 员工微信绑定审计：组织老板查看本组织员工的绑定、换绑与失效记录。
 */
@Tag(name = "平台 - 员工微信绑定审计")
@RestController
@RequestMapping("/platform/staff-wechat")
@Validated
public class StaffWechatBindingController {

    @Resource
    private StaffWechatBindingService bindingService;

    @GetMapping("/binding-events/page")
    @Operation(summary = "分页查询本组织员工的微信绑定审计")
    @PreAuthorize("@post.has('" + PlatformIdentityPermissions.BINDING_QUERY + "')")
    public CommonResult<PageResult<WechatBindingEventRespVO>> page(@Valid WechatBindingEventPageReqVO req) {
        return success(BeanUtils.toBean(bindingService.pageEvents(req, req.getUserId()), WechatBindingEventRespVO.class));
    }

}
