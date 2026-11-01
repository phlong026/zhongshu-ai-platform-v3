package cn.iocoder.yudao.module.platform.workflow.message;

import java.util.Set;

/**
 * 消息字典：与 pf_message 迁移 CHECK 约束同源（11 文档 §五.2）。
 * 新增值先在需求文档登记，再改本类 + 新迁移改 CHECK。
 */
public interface MessageConstants {

    String TYPE_BUSINESS = "BUSINESS";
    String TYPE_LEARNING = "LEARNING";
    String TYPE_SYSTEM = "SYSTEM";
    Set<String> TYPES = Set.of(TYPE_BUSINESS, TYPE_LEARNING, TYPE_SYSTEM);

    String ROUTE_CUSTOMER = "customer";
    String ROUTE_COURSE = "course";
    String ROUTE_PERSONAL_PERFORMANCE = "personal-performance";
    String ROUTE_PRIVACY = "privacy";
    String ROUTE_DESIGN_DISPATCH = "design-dispatch";
    String ROUTE_DESIGN_PROGRESS = "design-progress";
    Set<String> ROUTES = Set.of(ROUTE_CUSTOMER, ROUTE_COURSE, ROUTE_PERSONAL_PERFORMANCE,
            ROUTE_PRIVACY, ROUTE_DESIGN_DISPATCH, ROUTE_DESIGN_PROGRESS);

    /** 发送方域格式（登记表见 11 文档 §五.2，本期生产域=空） */
    String SENDER_DOMAIN_PATTERN = "[a-z][a-z0-9-]{0,31}";

    int TITLE_MAX_LENGTH = 100;
    int DESCRIPTION_MAX_LENGTH = 500;
    int DEDUPE_KEY_MAX_LENGTH = 128;

}
