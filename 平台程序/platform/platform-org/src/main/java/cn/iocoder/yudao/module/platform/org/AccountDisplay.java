package cn.iocoder.yudao.module.platform.org;

import java.util.Map;

/** 登录搜索、开户交付共用的人员及组织展示；不改变组织或账号标识。 */
public record AccountDisplay(String personName, String roleCode, String roleName, String shortOrgName) {
    private static final Map<String, String> ROLES = Map.ofEntries(
            Map.entry("boss", "老板"), Map.entry("owner", "门店负责人"),
            Map.entry("sales", "业务员"), Map.entry("sales-lead", "业务负责人"),
            Map.entry("engineer", "工程人员"), Map.entry("project-manager", "工程负责人"),
            Map.entry("finance", "财务专员"), Map.entry("finance-lead", "财务负责人"),
            Map.entry("hq-admin", "总部管理员"),
            Map.entry("hq-design-admin", "设计行政"), Map.entry("hq-designer", "设计师"),
            Map.entry("hq-design-lead", "设计负责人"), Map.entry("hq-operations", "运营专员"),
            Map.entry("hq-operations-lead", "运营负责人"));

    public static AccountDisplay of(String name, String role, String brand, String province, String city,
                                    String area, String fallbackOrg) {
        String p = region(province), c = region(city), label = text(brand);
        String district = text(area);
        if (!label.isEmpty()) district = district.replace(label, "").trim();
        String location = p + (p.equals(c) ? "" : c) + district;
        String organization = label.isEmpty() ? location : label + (location.isEmpty() ? "" : " · " + location);
        if (organization.isEmpty()) organization = text(fallbackOrg);
        return new AccountDisplay(text(name), text(role), ROLES.getOrDefault(text(role), "员工"), organization);
    }

    private static String text(String value) { return value == null ? "" : value.trim(); }
    private static String region(String value) { return text(value).replaceFirst("[省市]$", ""); }
}
