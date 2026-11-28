package cn.iocoder.yudao.module.franchise.api;

import cn.iocoder.yudao.module.platform.org.PostSnapshot;

/** Continue a uniquely identified preliminary project book inside the engineering filing transaction. */
public interface FinanceBookProjectPort {
    void linkPendingBooks(PostSnapshot post,long projectId);
}
