package cn.iocoder.yudao.module.platform.identity;

/**
 * 平台员工登录相关权限码。菜单与岗位授权由业务域迁移写入（如加盟协同域把 BINDING_QUERY 授予老板岗位）。
 */
public interface PlatformIdentityPermissions {

    /** 查看本组织员工的微信绑定审计 */
    String BINDING_QUERY = "platform:staff-wechat:binding-query";

}
