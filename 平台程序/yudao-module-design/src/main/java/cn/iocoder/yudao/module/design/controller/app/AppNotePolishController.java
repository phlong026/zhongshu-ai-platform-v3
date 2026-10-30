package cn.iocoder.yudao.module.design.controller.app;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.design.ai.NotePolishService;
import cn.iocoder.yudao.module.design.controller.app.vo.AppNotePolishReqVO;
import cn.iocoder.yudao.module.design.controller.app.vo.AppNotePolishRespVO;
import cn.iocoder.yudao.module.infra.zhongshu.api.IdentitySessionPort;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.annotation.security.PermitAll;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "小程序 - AI 设计补充需求润色")
@RestController
@RequestMapping("/design/v1/ai-design")
@PermitAll // 自有 user_session Bearer 校验在方法内强制执行（与资产控制器同范式）；需激活账号
public class AppNotePolishController {

    @Resource
    private NotePolishService notePolishService;

    @Resource
    private IdentitySessionPort identitySessionPort;

    @PostMapping("/note-polish")
    @Operation(summary = "补充需求 AI 优化：保留原意与数字，按线路语境改写为条理清晰的设计描述")
    public CommonResult<AppNotePolishRespVO> notePolish(
            @Valid @RequestBody AppNotePolishReqVO reqVO,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        long userId = requireAccountId(authorization);
        String polished = notePolishService.polish(userId, reqVO.getNote(), reqVO.getLine());
        return success(new AppNotePolishRespVO(polished));
    }

    private long requireAccountId(String authorization) {
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : authorization;
        return identitySessionPort.requireUnrestricted(token).accountId();
    }
}
