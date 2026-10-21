const A=require('./account');
function logout(){
 if(!A.load().session.loggedIn)return;
 wx.showModal({title:'退出登录',content:'确认退出当前账号？本地业务记录和草稿会保留。',confirmText:'退出登录',cancelText:'取消',confirmColor:'#89512a',success(result){if(!result.confirm)return;A.mutate(s=>A.logout(s));wx.reLaunch({url:'/pages/profile/index'});}});
}
module.exports={logout};
