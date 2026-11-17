const fs=require('fs'),vm=require('vm');let count=0;
for(const file of ['index.wxml','workbench.wxml','project-books.wxml','finance-home.wxml','journey.wxml','journey-forms.wxml','receipt-revision.wxml']){
 const source=fs.readFileSync('miniprogram/pages/admin/finance-desk/'+file,'utf8');
 for(const match of source.matchAll(/\{\{([\s\S]*?)\}\}/g)){
  const exp=match[1];try{new vm.Script('('+exp+')');}catch(e){try{new vm.Script('({'+exp+'})');}catch(_){throw Error(file+': '+exp+' => '+e.message);}}count++;
 }
}
console.log(count+' WXML binding expressions parsed');
