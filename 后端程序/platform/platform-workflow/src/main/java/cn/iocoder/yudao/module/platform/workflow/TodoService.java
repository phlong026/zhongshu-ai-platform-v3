package cn.iocoder.yudao.module.platform.workflow;

import cn.iocoder.yudao.module.platform.org.PostSnapshot;

public interface TodoService {

    /** 汇总各业务域的待办：可办理的在前，同类按时间新的在前；按 key 去重 */
    TodoList list(PostSnapshot post);

}
