/**
 * 加盟协同域（franchise）对外接口。
 *
 * <p>其他业务域需要加盟协同域的数据时，只能调用本包下的接口，拿到的是 DTO；
 * 不允许直接读写本域 fr_ 前缀的表。接口在 M2 起随业务线模块一起加入。
 */
package cn.iocoder.yudao.module.franchise.api;
