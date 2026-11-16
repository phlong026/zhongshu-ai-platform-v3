package cn.iocoder.yudao.module.platform.training.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.platform.training.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.*;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.module.platform.training.TrainingModel.*;

@RestController @RequestMapping("/training/admin") @PreAuthorize("@ss.hasRole('super_admin')")
public class TrainingAdminController {
    private final TrainingFiles files;private final TrainingCatalog catalog;private final TrainingLearning learning;
    public TrainingAdminController(TrainingFiles f,TrainingCatalog c,TrainingLearning l){files=f;catalog=c;learning=l;}
    @GetMapping("/options") public CommonResult<?> options(){return success(Map.of("categories",CATEGORIES,"roles",ROLES,"organizations",catalog.organizations(),"chunkSize",4*1024*1024));}
    @GetMapping("/courses") public CommonResult<?> courses(@RequestParam(defaultValue="")String keyword,@RequestParam(defaultValue="1")int page){return success(catalog.courses(keyword,page));}
    @PostMapping("/courses") public CommonResult<?> create(@RequestBody Save save){return success(catalog.save(null,save));}
    @GetMapping("/courses/{id}") public CommonResult<?> course(@PathVariable String id){return success(catalog.detail(id));}
    @PutMapping("/courses/{id}") public CommonResult<?> save(@PathVariable String id,@RequestBody Save save){return success(catalog.save(id,save));}
    @GetMapping("/courses/{id}/checks") public CommonResult<?> checks(@PathVariable String id){return success(catalog.issues(id));}
    @PostMapping("/courses/{id}/{action:submit|reject|publish|withdraw}") public CommonResult<?> action(@PathVariable String id,@PathVariable String action,@RequestBody Action in){return success(catalog.act(id,action,in));}
    @GetMapping("/material-categories") public CommonResult<?> categories(){return success(files.materialCategories());}
    @PostMapping("/material-categories") public CommonResult<?> createCategory(@RequestBody CategoryEdit input){return success(files.createMaterialCategory(input));}
    @PutMapping("/material-categories/{id}") public CommonResult<?> renameCategory(@PathVariable String id,@RequestBody CategoryEdit input){return success(files.renameMaterialCategory(id,input));}
    @DeleteMapping("/material-categories/{id}") public CommonResult<?> deleteCategory(@PathVariable String id,@RequestBody CategoryDelete input){return success(files.deleteMaterialCategory(id,input));}
    @GetMapping("/materials") public CommonResult<?> materials(@RequestParam(defaultValue="")String keyword,@RequestParam(defaultValue="")String category,@RequestParam(defaultValue="1")int page){return success(files.materials(keyword,category,page));}
    @GetMapping("/materials/{id}") public CommonResult<?> material(@PathVariable String id){return success(catalog.materialDetail(id));}
    @PutMapping("/materials/{id}") public CommonResult<?> editMaterial(@PathVariable String id,@RequestBody MaterialEdit input){return success(files.editMaterial(id,input));}
    @DeleteMapping("/materials/{id}") public CommonResult<?> deleteMaterial(@PathVariable String id,@RequestBody MaterialDelete input){files.deleteMaterial(id,input);return success(true);}
    @PostMapping("/materials/{id}/retry") public CommonResult<?> retry(@PathVariable String id){files.retry(id);return success(true);}
    @PostMapping("/materials/{id}/ticket") public CommonResult<?> ticket(@PathVariable String id,@RequestParam String purpose){return success(learning.adminTicket(id,purpose));}
    @PostMapping("/uploads") public CommonResult<?> begin(@RequestBody Upload input){return success(files.begin(input));}
    @GetMapping("/uploads/{id}") public CommonResult<?> upload(@PathVariable String id){return success(files.upload(id));}
    @PutMapping(value="/uploads/{id}/parts/{part}",consumes="application/octet-stream") public CommonResult<?> part(@PathVariable String id,@PathVariable int part,HttpServletRequest request)throws IOException{files.part(id,part,request.getInputStream());return success(true);}
    @PostMapping("/uploads/{id}/complete") public CommonResult<?> finish(@PathVariable String id){return success(files.finish(id));}
}
