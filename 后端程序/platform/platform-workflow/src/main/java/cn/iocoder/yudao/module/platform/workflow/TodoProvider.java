package cn.iocoder.yudao.module.platform.workflow;

import cn.iocoder.yudao.module.platform.org.PostSnapshot;

import java.util.List;

/**
 * 业务域的待办来源：按当前岗位即时计算（对应小程序各模块的 tasks()）。实现为 Spring Bean 即被自动汇总。
 */
public interface TodoProvider {

    String domain();

    List<TodoItem> list(PostSnapshot post);

}
