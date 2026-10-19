const a=require('miniprogram-automator');
(async()=>{const mp=await a.connect({wsEndpoint:'ws://127.0.0.1:9620'});try{await mp.screenshot({path:'qa/typography-20261018/current-native-frame.png'});console.log('captured');}finally{await mp.disconnect();}})().catch(e=>{console.error(e);process.exit(1)});
