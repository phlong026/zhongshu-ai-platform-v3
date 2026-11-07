const S=require('./sheet'),R=require('./render'),PDF=require('./pdf');
const call=(fn,options)=>new Promise((resolve,reject)=>fn({...options,success:resolve,fail:reject}));
Page({
 data:{title:'报价单导出',form:{},image:'',busy:false,error:'',allowed:false,total:'',label:''},
 onLoad(o){ // 纯 API 态：exportSheet 冻结快照数据源（本地报价分支已随切生产剔除下线——无 quote-id 即错误态）
  this.quoteId=o['quote-id'];
  if(!this.quoteId){this.setData({error:'请从报价记录进入导出（报价单由平台快照生成）',allowed:false});return;}
  this.loadPlatform();},
 onReady(){this.ready=true;if(this.doc)this.generate();},
 /** exportSheet → sheet doc 映射（12 文档 §五） */
 loadPlatform(){
  const http=require('../../../api/http');
  http.get('/franchise/sales/quotes/'+this.quoteId+'/export-sheet').then(data=>{
   const money=S.money,lines=(rows)=>(rows||[]).map(r=>({label:r.label||'',name:r.label||'',quantity:r.qtyText!=null?String(r.qtyText):(r.quantity!=null?String(r.quantity):'1'),unitPrice:r.unitText!=null?String(r.unitText):(r.unitPriceCents!=null?money(r.unitPriceCents):''),cents:r.valueCents==null?null:r.valueCents,note:''}));
   const main=lines(data.main),decor=lines(data.decor);
   const sum=rows=>rows.reduce((n,r)=>n+(r.cents||0),0);
   const mainTotal=sum(main),decorTotal=sum(decor),insurance=(data.insurance||0)===0?null:data.insurance,management=(data.management||0)===0?null:data.management;
   const total=data.total;
   if(mainTotal!==data.mainTotal||decorTotal!==data.decorTotal)return this.setData({error:'报价分项与小计不一致，请核对后再导出',allowed:false});
   if(mainTotal+decorTotal+(insurance||0)+(management||0)!==total)return this.setData({error:'报价分项与总价不一致，请核对后再导出',allowed:false});
   if(main.length+decor.length>14)return this.setData({error:'分项超出打印模板容量，请整理报价分项',allowed:false});
   const doc={kind:'budget',version:data.version,date:data.date||'',name:data.name||'',phone:data.phone||'',projectId:this.quoteId,address:data.address||'',form:S.clean({}),width:data.width==null?'':String(data.width),depth:data.depth==null?'':String(data.depth),area:data.area==null?'':String(data.area),floors:data.floors==null?'':String(data.floors),main,decor,mainTotal,decorTotal,insurance,management,total,title:'建造参考预算',basis:data.basis||'前期预算，非施工结算依据',demo:false,priceVersion:data.priceVersion||''};
   this.doc=doc;this.setData({form:doc.form,label:doc.title+' V'+doc.version,total:S.money(doc.total),allowed:true,error:''});
   if(this.ready)this.generate();
  }).catch(err=>{
   this.setData({error:(err&&err.message)||'导出数据加载失败，请重试',allowed:false});
  });
 },
 async generate(){if(this.data.busy||!this.ready||!this.doc)return;this.setData({busy:true,pdfFile:'',imageFile:''});try{
   const canvas=await new Promise((resolve,reject)=>wx.createSelectorQuery().in(this).select('#quote-canvas').fields({node:true,size:true}).exec(rows=>rows[0]?.node?resolve(rows[0].node):reject(Error('报价单画布未就绪，请重试'))));
   canvas.width=R.WIDTH*R.SCALE;canvas.height=R.HEIGHT*R.SCALE;const ctx=canvas.getContext('2d');ctx.scale(R.SCALE,R.SCALE);R.render(ctx,this.doc);
   const image=await call(wx.canvasToTempFilePath,{canvas,fileType:'png',x:0,y:0,width:canvas.width,height:canvas.height,destWidth:canvas.width,destHeight:canvas.height});
   const jpeg=await call(wx.canvasToTempFilePath,{canvas,fileType:'jpg',quality:0.98,x:0,y:0,width:canvas.width,height:canvas.height,destWidth:canvas.width,destHeight:canvas.height});
   this.jpeg=jpeg.tempFilePath;this.setData({image:image.tempFilePath,allowed:true,error:''});
  }catch(e){this.doc=null;this.jpeg='';this.setData({image:'',allowed:false,error:e.message||e.errMsg||'生成失败，请重试'});}finally{this.setData({busy:false});}},
 async ensure(){if(!this.doc||!this.data.image)await this.generate();if(!this.doc||!this.data.image)throw Error(this.data.error||'请先生成报价单');},
 async preview(){try{await this.ensure();wx.previewImage({current:this.data.image,urls:[this.data.image]});}catch(e){this.setData({error:e.message});}},
 async exportImage(){if(this.data.busy)return;try{await this.ensure();const fs=wx.getFileSystemManager(),filePath=wx.env.USER_DATA_PATH+'/'+S.filename(this.doc,'png');await call(fs.copyFile.bind(fs),{srcPath:this.data.image,destPath:filePath});this.imageFile=filePath;this.setData({imageFile:filePath,error:''});wx.showActionSheet({itemList:['保存图片到相册','发送图片文件'],success:async r=>{try{if(r.tapIndex===0){await call(wx.saveImageToPhotosAlbum,{filePath});wx.showToast({title:'已保存到相册',icon:'success'});}else if(wx.shareFileMessage)await call(wx.shareFileMessage,{filePath,fileName:S.filename(this.doc,'png')});else wx.previewImage({current:filePath,urls:[filePath]});}catch(e){const denied=/auth deny|auth denied|authorize|permission/.test(e.errMsg||'');this.setData({error:denied?'保存相册需要授权，可在小程序设置中开启相册权限':e.errMsg||'保存失败，请重试',albumDenied:denied});}}});}catch(e){this.setData({error:e.message||e.errMsg});}},
 async exportPdf(){if(this.data.busy)return;try{await this.ensure();this.setData({busy:true});const fs=wx.getFileSystemManager(),jpeg=await call(fs.readFile.bind(fs),{filePath:this.jpeg}),filePath=wx.env.USER_DATA_PATH+'/'+S.filename(this.doc,'pdf');await call(fs.writeFile.bind(fs),{filePath,data:PDF.pdf(jpeg.data,R.WIDTH*R.SCALE,R.HEIGHT*R.SCALE)});this.setData({pdfFile:filePath,error:''});await call(wx.openDocument,{filePath,fileType:'pdf',showMenu:true});}catch(e){this.setData({error:e.message||e.errMsg||'PDF导出失败，请重试'});}finally{this.setData({busy:false});}},
 async sharePdf(){try{await this.ensure();if(!this.data.pdfFile)return this.exportPdf();if(!wx.shareFileMessage)throw Error('请打开PDF后通过右上角菜单发送或保存');await call(wx.shareFileMessage,{filePath:this.data.pdfFile,fileName:S.filename(this.doc,'pdf')});}catch(e){this.setData({error:e.message||e.errMsg});}},
 openSettings(){wx.openSetting({});},
 back(){return require('../../../utils/page-navigation').back();}
});
