# B 独立公网、微信合法域名与四岗位真机联调准备

B 公网入口为 **https://platform.zszhj.com**，小程序 AppID **wxe584f6fad7b54487**，当前体验版 **1.0.20261115.2**。这是独立联调环境；不提交正式审核或发布，不将开发者工具验收称为用户手机验收。

## 已完成与现场证据

| 项目 | 现场结果 | 证据 |
| --- | --- | --- |
| DNS 与 HTTPS | 已有 B 解析指向现有主机；独立免费证书通过完整验证，有效至2027-02-12 UTC | public-https-role-checks.json |
| 自动续期 | certbot仅针对platform域的staging演练成功，未替换生产证书 | certificate-renewal-check.json |
| Nginx | 复用已有 `/etc/nginx/conf.d/zhongshu-platform-b-integration.conf`，唯一业务上游127.0.0.1:48081 | nginx-live-b.conf |
| 内部管理面 | 6类公网路径均404，B数据库/Redis/Provider/Runtime监听回环或私网 | public-management-path-denials.json |
| 微信合法域名 | request、uploadFile、downloadFile均保留旧条目并加入B API和上海B COS域；管理员验证完成后，刷新页面并逐项查看确认 | wechat-domain-save-status.json |
| 真实微信登录 | 公网real微信链路HTTP200/code0/needPhone=true，无伪造手机号授权或身份绑定 | real-wechat-login-probe.json |
| 四岗权限 | 老板、业务、财务、工程公网读接口成功；工程访问财务被403拒绝 | public-role-posts.json、public-https-role-checks.json |
| 四岗前端切换 | 同账号通过真实身份页进入业务、财务、工程、老板对应工作台，保留登录态 | native-public-role-switches.json |
| COS原生下载 | 域名校验开启，公网换取票据后wx.downloadFile下载两图均200，图片可解码 | native-public-cos-downloads.json |
| 微信上传 | 官方CLI两次终态成功，最终版本.2，后台确认.2为体验版 | official-upload-receipt-v2.json、wechat-experience-version.json |
| A保护与积分 | A JAR、两份Nginx哈希与两项服务启动时间不变，五项服务200/UP；B余额/账本93、赠点只发生1次 | server-final-a-protection.json |

首次完整配置检查时，原有B站点在conf.d中已生效；本轮候选额外放入sites-enabled导致同名警告。已按内容哈希严格移除本轮重复项，保留原B配置不变，完整nginx-t无警告后优雅reload。未修改A两个站点、证书、数据库或服务；没有重启A API/Runtime。

## 域名与隔离

| 类别 | 本次必需B域名 |
| --- | --- |
| request | https://platform.zszhj.com；https://zhongshu-platform-b-assets-1397776231.cos.ap-shanghai.myqcloud.com |
| uploadFile | 同上 |
| downloadFile | 同上 |

当前原始字节上传使用wx.request PUT，因此COS必须在request名单中。socket和web-view业务域名不是当前小程序路径的依赖，没有为可选项开新服务。既有域名条目保持原状；只操作B AppID，没有修改A小程序后台。

客户端默认 `API_BASE=https://platform.zszhj.com/admin-api`，钱包从同域派生 `/app-api`。仅微信开发者工具platform=devtools可读取zs-api-base/zs-wallet-base；真机和桌面微信忽略旧调试地址，防止A或回环地址残留覆盖B默认。没有清理用户token、岗位或业务缓存。public/private项目配置的urlCheck均为true；private文件被Git忽略，仅本机生效。DevTools旧基址覆盖已清理，现场公网检查未依赖SSH隧道。

源代码提交：c13880d0。10组基址行为、check:integration 66项、wallet 8项、账号/微信登录检查通过；JS语法、diff、凭据扫描及反向补丁检查通过，独立审查APPROVE。没有新增依赖；未推送远端。

## 手机测试步骤

1. 使用已加入B体验成员或管理员/开发者的微信，扫描本目录 `B-experience-1.0.20261115.2.png`；后台显示二维码11月22日前有效。若进入旧版，退出小程序后重新扫描，并核对版本.2。
2. 在登录页同意协议，选择微信手机号授权登录，使用此前配置测试岗位的手机号。当前联调短信功能关闭，不发送短信、不新开付费短信服务。
3. 进入“我的 → 公司与工作身份”，选择联调公司对应的老板、业务员、财务专员、工程人员。该账号还保留既有总部岗位，测试本公司流程时选择门店/联调公司对应岗位。
4. 老板查看公司积分和成员；业务测试客户、预算、润色、设计；财务测试收付款只读与授权流程；工程测试工程列表与业务权限。切换后分别核对工作台和数据归属。

同人四岗用于联合调试，正式双人复核仍校验不同userId。现有公司测试积分93，不需要充值；真实AI调用仍使用已有供应商额度。本轮没有重复生成图片或增加积分。

## 仍需用户完成

本人手机首次getPhoneNumber授权、体验成员资格及实际四岗位业务闭环验收。服务端与开发工具已经验证，不能代替手机截图、真实微信授权和业务实单证据。若首次授权使用不同手机号，需要在B正常分配岗位，不能绕过员工绑定。

## 回滚与费用边界

客户端发生问题时，单独回退有问题的逻辑并保留 B API_BASE 和真机隔离规则，再上传新的 B 修复包。不得直接部署整提交的反向补丁或10月旧包，因为它们可能恢复 A 默认地址；源码反向补丁仅证明可撤销，不代表可安全切回 A。已有B公网站点、DNS与证书本轮未替换；如未来停用B入口，只操作该B conf并先nginx-t再优雅reload，不动A站点或A进程。微信域名只回退本次新增的B条目，保留其余原条目。

没有购买域名、云盘、KMS、快照、CDN或短信套餐，没有新开付费项目；仍使用现有主机、现有桶、已有供应商与免费证书。已有服务的正常流量/存储/供应商调用按既有账单计算，不宣称所有运行成本为零。

参考：[Nginx优雅重载](https://nginx.org/en/docs/control.html)、[微信网络域名要求](https://developers.weixin.qq.com/miniprogram/dev/framework/ability/network.html)、[getDeviceInfo平台标识](https://developers.weixin.qq.com/miniprogram/dev/api/base/system/wx.getDeviceInfo.html)。
