package cn.iocoder.yudao.module.platform.identity;

import cn.iocoder.yudao.framework.common.biz.system.oauth2.OAuth2TokenCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.oauth2.dto.OAuth2AccessTokenCreateReqDTO;
import cn.iocoder.yudao.framework.common.biz.system.oauth2.dto.OAuth2AccessTokenRespDTO;
import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.module.system.enums.oauth2.OAuth2ClientConstants;
import cn.iocoder.yudao.module.system.service.oauth2.OAuth2TokenService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

@Component
public class YudaoTokenIssuer implements TokenIssuer {

    @Resource
    private OAuth2TokenCommonApi oauth2TokenApi;
    @Resource
    private OAuth2TokenService oauth2TokenService;

    @Override
    public IssuedToken issue(long userId) {
        OAuth2AccessTokenCreateReqDTO req = new OAuth2AccessTokenCreateReqDTO();
        req.setUserId(userId);
        req.setUserType(UserTypeEnum.ADMIN.getValue());
        req.setClientId(OAuth2ClientConstants.CLIENT_ID_DEFAULT);
        OAuth2AccessTokenRespDTO token = oauth2TokenApi.createAccessToken(req);
        return new IssuedToken(token.getAccessToken(), token.getRefreshToken(), token.getExpiresTime());
    }

    @Override
    public void revokeAll(long userId) {
        oauth2TokenService.removeAccessToken(userId, UserTypeEnum.ADMIN.getValue());
    }

}
