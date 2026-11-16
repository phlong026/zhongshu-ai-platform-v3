package cn.iocoder.yudao.module.platform.training.controller.admin;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.platform.training.*;
import org.springframework.web.bind.annotation.*;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
@RestController @RequestMapping("/training/app")
public class TrainingAppController {
    private final TrainingCatalog catalog;private final TrainingLearning learning;
    public TrainingAppController(TrainingCatalog c,TrainingLearning l){catalog=c;learning=l;}
    @GetMapping("/courses") public CommonResult<?> courses(@RequestParam(defaultValue="")String keyword){return success(catalog.learnerCourses(keyword));}
    @GetMapping("/courses/{id}") public CommonResult<?> detail(@PathVariable String id){return success(catalog.learnerDetail(id));}
    @GetMapping("/materials") public CommonResult<?> resources(@RequestParam(defaultValue="")String keyword){return success(catalog.learnerResources(keyword));}
    @PostMapping("/courses/{id}/materials/{version}/ticket") public CommonResult<?> ticket(@PathVariable String id,@PathVariable String version,@RequestParam String purpose){return success(learning.learnerTicket(id,version,purpose));}
    @PostMapping("/courses/{id}/lessons/{lesson}/play") public CommonResult<?> play(@PathVariable String id,@PathVariable String lesson){return success(learning.begin(id,lesson));}
    @PostMapping("/progress") public CommonResult<?> beat(@RequestBody TrainingModel.Beat beat){return success(learning.heartbeat(beat,false));}
    @PostMapping("/read") public CommonResult<?> read(@RequestBody TrainingModel.Beat beat){return success(learning.heartbeat(beat,true));}
}
