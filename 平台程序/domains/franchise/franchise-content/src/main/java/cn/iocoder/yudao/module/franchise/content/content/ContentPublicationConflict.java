package cn.iocoder.yudao.module.franchise.content.content;

import cn.iocoder.yudao.module.franchise.content.dal.FrContentPublicationDO;
import java.util.Map;

import static cn.iocoder.yudao.module.franchise.content.enums.FranchiseContentErrorCodes.PUBLICATION_EXISTS;

/** Local publication conflict. Only an owner-scoped record may be passed to this response. */
public final class ContentPublicationConflict extends RuntimeException {
    private final Map<String, Object> details;

    public ContentPublicationConflict(FrContentPublicationDO existing, boolean sameInput) {
        super(PUBLICATION_EXISTS.getMsg());
        details = Map.of("existingPublicationId", existing.getId().toString(),
                "status", existing.getStatus(), "sameInput", sameInput);
    }

    public Map<String, Object> details() { return details; }
}
