package cn.iocoder.yudao.module.platform.workflow.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.workflow.TodoList;
import cn.iocoder.yudao.module.platform.workflow.TodoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 平台待办：汇总各业务域为当前岗位即时计算的待办；登录即可查看，不挂菜单权限。
 */
@Tag(name = "平台 - 待办")
@RestController
@RequestMapping("/platform/todos")
public class PlatformTodoController {

    @Resource
    private TodoService todoService;
    @Resource
    private CurrentPost currentPost;

    @GetMapping
    @Operation(summary = "当前岗位的待办列表（可办理在前；failedDomains 非空时提示部分待办暂时无法加载）")
    public CommonResult<TodoList> list() {
        return success(todoService.list(currentPost.require()));
    }

}
