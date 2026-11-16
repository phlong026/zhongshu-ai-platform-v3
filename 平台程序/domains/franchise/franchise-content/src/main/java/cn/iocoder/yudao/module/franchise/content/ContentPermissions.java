package cn.iocoder.yudao.module.franchise.content;

/** 内容创作获客权限码（阶段 1.5 / 855 矩阵）：query/write/persona 三码分离；persona 维护限 sales-lead/boss/owner。 */
public final class ContentPermissions {

    public static final String QUERY = "franchise:content:query";
    public static final String WRITE = "franchise:content:write";
    public static final String PERSONA = "franchise:content:persona";
    public static final String PERSONA_SELF = "franchise:content:persona:self";
    public static final String VERIFY = "franchise:content:verify";

    private ContentPermissions() {
    }
}
