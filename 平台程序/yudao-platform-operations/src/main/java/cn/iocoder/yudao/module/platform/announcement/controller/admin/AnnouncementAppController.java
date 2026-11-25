package cn.iocoder.yudao.module.platform.announcement.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.platform.announcement.AnnouncementService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.module.platform.announcement.AnnouncementModel.*;

/** The mini program reads announcements independently from the personal message inbox. */
@RestController
@RequestMapping("/platform/announcements/app")
public class AnnouncementAppController {
    private final AnnouncementService service;
    public AnnouncementAppController(AnnouncementService service) { this.service = service; }

    @ModelAttribute
    public void privateResponse(HttpServletResponse response) { response.setHeader("Cache-Control", "private, no-store"); }

    @GetMapping
    public CommonResult<Page<Summary>> list(@RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "1") int pageNo, @RequestParam(defaultValue = "20") int pageSize) {
        return success(service.appList(keyword, pageNo, pageSize));
    }

    @GetMapping("/{id}")
    public CommonResult<Detail> detail(@PathVariable String id) { return success(service.appDetail(id)); }
}
