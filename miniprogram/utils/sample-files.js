// Sample attachments are a test aid: they are offered only outside the release build.
const release=()=>{try{return wx.getAccountInfoSync().miniProgram.envVersion==='release';}catch(e){return false;}};
module.exports={release,items:list=>release()?list.slice(0,1):list};
