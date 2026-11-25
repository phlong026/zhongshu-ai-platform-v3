package cn.iocoder.yudao.module.platform.headquarters;

import cn.iocoder.yudao.framework.common.exception.ServiceException;

/** Fixed SQL sources and exact historical-reference names for headquarters external entrustment reads. */
public enum HeadquartersExternalEntrustKind {
    REQUESTS("requests", "fr_commission_external_version", "r.data->'attachments'", "EXTERNAL_REQUEST"),
    RESULTS("results", "fr_commission_external_result", "r.attachments", "EXTERNAL_RESULT");

    final String path, table, attachments, prefix;
    HeadquartersExternalEntrustKind(String path, String table, String attachments, String prefix) {
        this.path=path; this.table=table; this.attachments=attachments; this.prefix=prefix;
    }
    public static HeadquartersExternalEntrustKind fromPath(String path) {
        for (var value : values()) if (value.path.equals(path)) return value;
        throw new ServiceException(400, "外部委托资料类别无效");
    }
    public String section(int version) {
        if (version < 1) throw new ServiceException(400, "资料版本无效");
        return prefix + ":" + version;
    }
    record Version(HeadquartersExternalEntrustKind kind, int version) { }
    static Version fromSection(String section) {
        for (var value : values()) if (section.startsWith(value.prefix + ":")) {
            String suffix=section.substring(value.prefix.length()+1);
            if (!suffix.matches("[1-9][0-9]{0,9}")) throw HeadquartersReadGuard.missing();
            try { return new Version(value, Integer.parseInt(suffix)); }
            catch (NumberFormatException invalid) { throw HeadquartersReadGuard.missing(); }
        }
        throw HeadquartersReadGuard.missing();
    }
}
