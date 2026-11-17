const a=require('miniprogram-automator'),fs=require('fs'),assert=require('assert/strict');
const root='C:/Users/Administrator/.codex/artifacts/finance-joint-20261117/run1/',out='docs/finance-fullflow-20261117/',pause=ms=>new Promise(r=>setTimeout(r,ms));
(async()=>{const m=await a.connect({wsEndpoint:'ws://127.0.0.1:9973'});try{
 await m.evaluate(()=>{wx.setStorageSync('zs-api-base','http://127.0.0.1:18091/admin-api');wx.removeStorageSync('zs-api-token-v1');wx.removeStorageSync('zs-api-post-v1');wx.removeStorageSync('zs-api-posts-v1');});
 let p=await m.reLaunch('/pages/account/index?view=login');await pause(1500);p=await m.currentPage();
 const click=async label=>{for(const b of await p.$$('button'))if((await b.text()).trim()===label){await b.tap();await pause(300);return;}throw Error('Missing login control '+label);};
 await click('账号密码登录');await m.screenshot({path:out+'01-login.png'});
 await click('登录');assert.ok(await m.evaluate(()=>!!getCurrentPages().slice(-1)[0].data.error));
 const login=JSON.parse(fs.readFileSync(root+'test-logins.json','utf8')).finance;
 const query=await p.$('input[data-key="accountQuery"]');await query.input('联调财务');await query.trigger('confirm');await pause(1000);
 const choice=await p.$('.account-match[data-account-id="'+login.account+'"]');if(choice)await choice.tap();assert.ok(await m.evaluate(()=>!!getCurrentPages().slice(-1)[0].data.selectedAccount));
 await(await p.$('input[data-key="password"]')).input(login.password);await click('登录');
 if(await m.evaluate(()=>getCurrentPages().slice(-1)[0].data.consentOpen)){await(await p.$('.login-consent-check')).tap();await click('同意并登录');}
 await pause(2000);p=await m.currentPage();
 const session=await m.evaluate(()=>({token:wx.getStorageSync('zs-api-token-v1'),postId:wx.getStorageSync('zs-api-post-v1'),posts:wx.getStorageSync('zs-api-posts-v1')}));assert.ok(session.token);assert.ok(session.posts.find(x=>String(x.postId)===String(session.postId)&&x.roleCode==='finance'));
 const actors=JSON.parse(fs.readFileSync(root+'actors.json','utf8'));actors.finance={...actors.finance,...session};fs.writeFileSync(root+'actors.json',JSON.stringify(actors));
 await m.screenshot({path:out+'02-finance-home.png'});
 fs.writeFileSync(out+'login-result.json',JSON.stringify({passed:true,checks:['empty credentials blocked','account found through actual account lookup','password login and consent','finance role entered'],path:p.path},null,2));console.log('Actual finance account UI login passed');
 }finally{m.disconnect();}})().then(()=>process.exit(0),e=>{console.error(e.stack);process.exit(1)});
