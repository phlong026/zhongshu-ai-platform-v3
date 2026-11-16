package cn.iocoder.yudao.module.franchise.design.controller.admin;
import cn.iocoder.yudao.module.franchise.design.*;
import cn.iocoder.yudao.module.franchise.api.DesignServicePort;
import cn.iocoder.yudao.module.franchise.api.capability.DesignFlowCall;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@RequestMapping("/franchise/design")
@PreAuthorize("@post.has('"+DesignPermissions.USE+"')")
public class DesignFlowController {
    private final DesignFlowAccessService flow;
    private final DesignAccessService access;
    public DesignFlowController(DesignFlowAccessService flow,DesignAccessService access){this.flow=flow;this.access=access;}
    @PostMapping("/flow")
    public CommonResult<Object> execute(@RequestBody DesignFlowCall call,@RequestHeader(value="Idempotency-Key",required=false) String key) {
        String actual=key==null?"flow-"+java.util.UUID.randomUUID():key;
        if(actual.isBlank()||actual.length()>100)throw new IllegalArgumentException("Invalid business key");
        return success(flow.invoke(access.post(),call.withKey(actual)));
    }
    @GetMapping("/flow-price")
    public CommonResult<?> price(@RequestParam String subject,@RequestParam int quantity){return success(flow.quote(access.post(),subject,quantity));}
    @PostMapping("/flow-upload-ticket")
    public CommonResult<?> upload(@RequestBody DesignServicePort.UploadTicketCreate input){return success(flow.upload(access.post(),input));}
    @GetMapping("/flow-ai-jobs/{id}")
    public CommonResult<?> job(@PathVariable long id){return success(flow.job(access.post(),id,false,null));}
    @PostMapping("/flow-ai-jobs/{id}/cancellation-requests")
    public CommonResult<?> cancel(@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false) String key){return success(flow.job(access.post(),id,true,key==null?"cancel-"+java.util.UUID.randomUUID():key));}
}
