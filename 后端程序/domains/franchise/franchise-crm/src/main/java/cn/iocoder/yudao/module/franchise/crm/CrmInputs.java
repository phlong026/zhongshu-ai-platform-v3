package cn.iocoder.yudao.module.franchise.crm;

import cn.hutool.core.util.StrUtil;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.crm.enums.FranchiseCrmErrorCodes.NOTE_REQUIRED;

/** 客资与客户共用的输入校验 */
final class CrmInputs {

    static final int NOTE_MAX = 500;

    private CrmInputs() {
    }

    /** 必填说明（跟进内容、作废原因、转交原因），去首尾空白后 1–500 字 */
    static String note(String value) {
        String note = StrUtil.trim(value);
        if (StrUtil.isBlank(note) || note.length() > NOTE_MAX) {
            throw exception(NOTE_REQUIRED);
        }
        return note;
    }

}
