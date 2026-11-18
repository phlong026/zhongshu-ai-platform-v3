const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const path=require('node:path');
const root=path.resolve(__dirname,'..');
function component(rows,scope='engineer',appearance=''){
 let definition;
 vm.runInNewContext(fs.readFileSync(path.join(root,'miniprogram/components/todo-preview/index.js'),'utf8'),{Component:v=>definition=v});
 const events=[];
 const c={data:{...definition.data,rows,scope,appearance},setData(v){Object.assign(this.data,v);},triggerEvent(name,detail){events.push({name,detail});},...definition.methods};
 c.update=(items,identity=c.data.scope)=>{Object.assign(c.data,{rows:items,scope:identity});definition.observers['rows, scope'].call(c,items,identity);};
 c.update(rows);return {c,events};
}
const rows=n=>Array.from({length:n},(_,i)=>({key:'task-'+i,title:'工程 '+i,clickable:true,path:'/pages/build/record/index?id='+i}));
test('首页待办显示前三条和剩余条数，展开后第四条仍指向原单',()=>{
 const {render}=require('./role-page-runtime.cjs');const file=path.join(root,'miniprogram/components/todo-preview/index.wxml');
 const list=rows(7),{c,events}=component(list,'finance','home');
 const flatten=ns=>ns.flatMap(n=>[n,...flatten(n.children)]),text=n=>n.text||n.children.map(text).join('');
 function screen(){const nodes=flatten(render(file,c.data));return {button:nodes.find(n=>n.attrs?.bindtap==='toggle'),rows:nodes.filter(n=>n.attrs?.bindtap==='activate')};}
 let view=screen();assert.equal(view.rows.length,3);assert.equal(text(view.button),'另 4 项');c.toggle();view=screen();assert.equal(view.rows.length,7);assert.equal(text(view.button),'收起');
 c.activate({currentTarget:{dataset:{index:view.rows[3].attrs['data-index']}}});assert.equal(events[0].detail.item.path,list[3].path);c.toggle();assert.equal(screen().rows.length,3);
 c.update(list.slice(0,3));assert.equal(screen().button,undefined);
});
for(const n of [0,1,3,4,20])test(n+' tasks show at most three with an exact remaining count',()=>{
 const {c}=component(rows(n));assert.equal(c.data.visibleRows.length,Math.min(n,3));assert.equal(c.data.remaining,Math.max(0,n-3));
 c.toggle();assert.equal(c.data.visibleRows.length,n);c.toggle();assert.equal(c.data.visibleRows.length,Math.min(n,3));
});
test('expanded fourth task preserves its original destination and disabled tasks cannot navigate',()=>{
 const list=rows(5),{c,events}=component(list);list[4].clickable=false;c.toggle();
 c.activate({currentTarget:{dataset:{index:3}}});assert.strictEqual(events[0].detail.item,list[3]);
 c.activate({currentTarget:{dataset:{index:4}}});assert.equal(events.length,1);
});
test('role change collapses the list; completing tasks removes a stale expanded state',()=>{
 const {c}=component(rows(8));c.toggle();c.update(rows(9));assert.equal(c.data.visibleRows.length,9);
 c.update(rows(7),'finance');assert.equal(c.data.visibleRows.length,3);c.toggle();c.update(rows(1));assert.equal(c.data.expanded,false);c.update(rows(8));assert.equal(c.data.visibleRows.length,3);
});
test('finance and design fields retain business context, status and amount',()=>{
 const {c}=component([{key:'fin',company:'测试公司',name:'材料采购',status:'部分付款',amount:'¥123,456.78'},{id:'design',name:'设计项目',detail:'施工图待下发',actionUrl:'/pages/workflow/commission/index?id=1'}]);
 assert.equal(c.data.visibleRows[0].title,'测试公司 · 材料采购');assert.equal(c.data.visibleRows[0].amount,'¥123,456.78');assert.equal(c.data.visibleRows[0].meta,'部分付款');assert.equal(c.data.visibleRows[1].meta,'施工图待下发');
});
