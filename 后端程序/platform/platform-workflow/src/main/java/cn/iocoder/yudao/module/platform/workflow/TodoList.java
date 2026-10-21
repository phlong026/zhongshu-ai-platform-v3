package cn.iocoder.yudao.module.platform.workflow;

import java.util.List;

/**
 * @param failedDomains 本次未能取到待办的业务域；前端应提示「部分待办暂时无法加载」，而不是当作没有待办
 */
public record TodoList(List<TodoItem> items, int actionableCount, List<String> failedDomains) {
}
