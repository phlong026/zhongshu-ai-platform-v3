package cn.iocoder.yudao.module.franchise.content.content;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.franchise.api.AssetAccessPort;
import cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import org.springframework.stereotype.Service;
import java.util.Set;

import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.RESOURCE_FORBIDDEN;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.content.enums.FranchiseContentErrorCodes.PUBLICATION_COVER_INVALID;

/** A cover is a persisted, verified image owned by the operator; client URLs are never trusted. */
@Service
public class ContentPublicationCoverService {
    private final CurrentPost posts;
    private final StoreServiceAccountPort accounts;
    private final AssetAccessPort assets;

    public ContentPublicationCoverService(CurrentPost posts, StoreServiceAccountPort accounts, AssetAccessPort assets) {
        this.posts = posts; this.accounts = accounts; this.assets = assets;
    }

    public Long validate(long companyId, long userId, Object value) {
        if (value == null) return null;
        String text = value.toString();
        long id;
        try {
            if (!text.matches("[1-9][0-9]{0,18}")) throw invalid();
            id = Long.parseLong(text);
        } catch (NumberFormatException error) { throw invalid(); }
        var post = posts.require();
        if (post.orgId() != companyId || post.userId() != userId) throw exception(RESOURCE_FORBIDDEN);
        var actor = new AssetAccessPort.Actor(companyId, accounts.requireServiceAccount(companyId), userId, post.postId());
        // Unlike general library visibility, this port also enforces the uploader's ownership and digest.
        var image = assets.readContentMedia(actor, id);
        if (image == null || image.mimeType() == null || !Set.of("image/jpeg", "image/png").contains(image.mimeType())
                || image.bytes() == null || image.bytes().length == 0) throw invalid();
        return id;
    }

    private static ServiceException invalid() {
        return exception(PUBLICATION_COVER_INVALID);
    }
}
