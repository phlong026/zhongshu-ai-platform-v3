package cn.iocoder.yudao.module.platform.identity.controller.admin.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class AccountMatchesRespVO {
    private List<Item> items;
    private Boolean hasMore;

    @Data
    @AllArgsConstructor
    public static class Item {
        private String accountId;
        private String loginName;
        private String orgName;
        private String ownerName;
    }
}
