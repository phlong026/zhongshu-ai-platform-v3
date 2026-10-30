package cn.iocoder.yudao.module.platform.workflow;

import java.time.OffsetDateTime;

/**
 * 一条待办。
 *
 * @param key         全局唯一（建议「业务域:事项:编号:版本」），用于去重
 * @param amountCents 涉及金额（分），无则为 null
 * @param actionable  当前岗位能否办理；false 表示「办理中，等待他人」
 * @param webUrl      Web 后台跳转地址
 * @param appPath     小程序页面路径
 * @param since       事项进入当前状态的时间，用于排序
 */
public record TodoItem(String domain, String key, String title, String status, Long amountCents,
                       boolean actionable, String webUrl, String appPath, OffsetDateTime since) {
}
