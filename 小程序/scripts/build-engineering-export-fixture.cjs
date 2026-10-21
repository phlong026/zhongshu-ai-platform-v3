const fs=require('fs');
(async()=>{
 await require('./role-page-runtime.cjs').connect();
 const file='qa/engineering-finance-audit-20261021/engineering-native-fixtures.json',f=JSON.parse(fs.readFileSync(file)),E=require('../miniprogram/pages/build/model');
 for(const [k,v]of JSON.parse(JSON.stringify(f.working)))wx.setStorageSync(k,v);
 const business=wx.getStorageSync('zhongshu-business-v3'),customer=JSON.parse(JSON.stringify(business.customers[0]));
 customer.id+='-export-B';customer.projectId+='-export-B';customer.name='模拟导出工程B';customer.phone='13900002222';business.customers.push(customer);wx.setStorageSync('zhongshu-business-v3',business);
 const engineering=wx.getStorageSync(E.KEY),project=JSON.parse(JSON.stringify(engineering.projects[0]));
 project.id+='-export-B';project.projectId=customer.projectId;project.sourceCustomerId=customer.id;project.name='模拟导出工程B';project.customer=customer.name;engineering.projects.push(project);wx.setStorageSync(E.KEY,engineering);
 E.apply(f.engineeringId,'plan',{});E.apply(project.id,'plan',{});
 f.exportPair={rows:wx.getStorageInfoSync().keys.map(k=>[k,wx.getStorageSync(k)]),projects:[f.engineeringId,project.id]};
 fs.writeFileSync(file,JSON.stringify(f,null,2));console.log('Two distinct projects with saved V1 plans prepared');
})().catch(e=>{console.error(e.stack);process.exitCode=1;});
