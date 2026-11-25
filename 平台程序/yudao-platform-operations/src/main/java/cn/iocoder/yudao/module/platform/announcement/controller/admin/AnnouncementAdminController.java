package cn.iocoder.yudao.module.platform.announcement.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.platform.announcement.AnnouncementService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.module.platform.announcement.AnnouncementModel.*;

/** Authorization is enforced by AnnouncementAccess on every service operation. */
@RestController
@RequestMapping("/platform/announcements/admin")
public class AnnouncementAdminController {
    private final AnnouncementService service;
    public AnnouncementAdminController(AnnouncementService service) { this.service = service; }

    @ModelAttribute
    public void privateResponse(HttpServletResponse response) { response.setHeader("Cache-Control", "private, no-store"); }

    @GetMapping("/options")
    public CommonResult<Options> options() { return success(service.options()); }

    @GetMapping
    public CommonResult<Page<Summary>> list(@RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "") String status, @RequestParam(defaultValue = "") String brandId,
            @RequestParam(defaultValue = "1") int pageNo, @RequestParam(defaultValue = "20") int pageSize) {
        return success(service.adminList(keyword, status, brandId, pageNo, pageSize));
    }

    @GetMapping("/{id}")
    public CommonResult<Detail> detail(@PathVariable String id) { return success(service.adminDetail(id)); }

    @PostMapping
    public CommonResult<Detail> create(@RequestBody Save input) { return success(service.create(input)); }

    @PutMapping("/{id}")
    public CommonResult<Detail> update(@PathVariable String id, @RequestBody Save input) { return success(service.update(id, input)); }

    @PostMapping("/{id}/publish")
    public CommonResult<Detail> publish(@PathVariable String id, @RequestBody Action input) { return success(service.publish(id, input)); }

    @PostMapping("/{id}/withdraw")
    public CommonResult<Detail> withdraw(@PathVariable String id, @RequestBody Action input) { return success(service.withdraw(id, input)); }
}
