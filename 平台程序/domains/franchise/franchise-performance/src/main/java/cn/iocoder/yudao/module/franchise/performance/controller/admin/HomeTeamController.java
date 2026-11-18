package cn.iocoder.yudao.module.franchise.performance.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.franchise.performance.content.HomeTeamService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@RequestMapping("/franchise/home/team")
@PreAuthorize("@post.has('franchise:performance:query')")
public class HomeTeamController {
    private final CurrentPost current;
    private final HomeTeamService team;
    public HomeTeamController(CurrentPost current, HomeTeamService team) {this.current=current; this.team=team;}

    @GetMapping
    public CommonResult<?> team(@RequestParam String area, @RequestParam String period) {
        return success(team.members(current.require(),area,period));
    }
    @GetMapping("/{userId}")
    public CommonResult<?> records(@PathVariable long userId,@RequestParam String area,@RequestParam String period,
                                   @RequestParam String kind,@RequestParam(defaultValue="1") int page) {
        return success(team.records(current.require(),area,period,userId,kind,page));
    }
    @GetMapping("/focus")
    public CommonResult<?> focus(@RequestParam String area,@RequestParam String period,@RequestParam(defaultValue="1") int page) {
        return success(team.focus(current.require(),area,period,page));
    }
}
