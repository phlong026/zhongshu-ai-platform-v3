package cn.iocoder.yudao.module.platform.workflow.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.workflow.message.MessageCenterService;
import org.springframework.web.bind.annotation.*;
import java.time.OffsetDateTime;
import java.util.Map;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@RequestMapping("/platform/messages/center")
public class MessageCenterController {
    private final CurrentPost current;
    private final MessageCenterService service;
    public MessageCenterController(CurrentPost current,MessageCenterService service){this.current=current;this.service=service;}
    @GetMapping public CommonResult<MessageCenterService.Page> list(
        @RequestParam(required=false) String type,@RequestParam(required=false) String module,
        @RequestParam(required=false) String keyword,@RequestParam(required=false) String mode,
        @RequestParam(required=false) Long beforeId,@RequestParam(required=false) Integer limit) {
        return success(service.list(current.require(),new MessageCenterService.Query(type,module,keyword,mode,beforeId,limit)));
    }
    @GetMapping("/summary") public CommonResult<Map<String,Long>> summary(){return success(Map.of("count",service.unread(current.require())));}
    public record Open(String id) { }
    @PostMapping("/open") public CommonResult<Map<String,Object>> open(@RequestBody Open request){return success(service.open(current.require(),request.id()==null?"":request.id()));}
    public record ReadAll(OffsetDateTime through) { }
    @PostMapping("/read-all") public CommonResult<Map<String,Integer>> readAll(@RequestBody(required=false) ReadAll request){return success(Map.of("updated",service.readAll(current.require(),request==null?null:request.through())));}
}
