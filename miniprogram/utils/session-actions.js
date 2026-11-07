const A=require('./account');
// M02-T03/I04：通用退出统一清理范围——演示态（本地会话）与平台 API 令牌两条链都处理，
// 纯 API 登录（本地无演示会话）同样可退出；本地业务记录和草稿保留（与文案一致）。
function logout(){
 const localLoggedIn=A.load().session.loggedIn;
 wx.showModal({title:'退出登录',content:'确认退出当前账号？本地业务记录和草稿会保留。',confirmText:'退出登录',cancelText:'取消',confirmColor:'#89512a',success(result){if(!result.confirm)return;
  let failMsg='';
  if(localLoggedIn){try{A.mutate(s=>A.logout(s));}catch(e){failMsg='本地退出异常';}} // 演示态损坏不阻断 API 退出（Codex R1 P2-3）
  try{require('../api/auth').logout();}catch(e){failMsg=failMsg||'令牌清理异常';}
  wx.reLaunch({url:'/pages/profile/index'});
  if(failMsg)wx.showToast({title:failMsg+'，请重新登录确认',icon:'none'});}});
}
module.exports={logout};
