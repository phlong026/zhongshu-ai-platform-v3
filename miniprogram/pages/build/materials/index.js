// 材料页壳：工程工作台 Tab 入口与旧链接仍指向本页，加载即转跳 record 材料视图（record 页自行处理空态）
Page({
 data:{error:''},
 onLoad(o){wx.redirectTo({url:'/pages/build/record/index?view=materials&id='+encodeURIComponent(o.id||'')+(o.nodeId?'&nodeId='+encodeURIComponent(o.nodeId):'')});}
});
