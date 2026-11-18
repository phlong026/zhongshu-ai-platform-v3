package cn.iocoder.yudao.module.franchise.performance.controller.admin;
import cn.iocoder.yudao.module.franchise.performance.content.HomeMetricsService;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@RequestMapping("/franchise/home")
@PreAuthorize("@post.has('franchise:performance:query')")
public class HomeMetricsController {
    private final CurrentPost current;private final HomeMetricsService service;
    public HomeMetricsController(CurrentPost current,HomeMetricsService service){this.current=current;this.service=service;}
    @GetMapping public CommonResult<?> home(@RequestParam String role,@RequestParam String period){return success(service.home(current.require(),role,period));}
    @GetMapping("/metrics/{key}") public CommonResult<?> detail(@PathVariable String key,@RequestParam String role,@RequestParam String period,@RequestParam(defaultValue="1") int page){return success(service.detail(current.require(),role,period,key,page));}

    @GetMapping("/metrics/{key}/record")
    public CommonResult<?> record(@PathVariable String key, @RequestParam String role, @RequestParam String period,
                                  @RequestParam String type, @RequestParam long id) {
        return success(service.financialRecord(current.require(), role, period, key, type, id));
    }
}
