// A selected record owns its parent IDs. Do not carry a previous batch into it.
const batchKeys=['materialId','supplyId','receiptId','reshipId'];
const recordKeys=batchKeys.concat(['inspectionId','rectificationId','collectionId','record']);
function resolve(p,current={},requested={},view=''){
 const explicit=Object.fromEntries(Object.entries(requested).filter(([,v])=>v!==undefined)),next={...current,...explicit};
 const has=k=>Object.prototype.hasOwnProperty.call(explicit,k),changed=k=>!!explicit[k]&&explicit[k]!==current[k];
 const clear=keys=>keys.forEach(k=>{if(!has(k))delete next[k];}),inherit=(k,v)=>{if(!has(k)&&v)next[k]=v;};
 if(!p)return next;
 const material=id=>{const m=(p.materials||[]).find(r=>r.id===id);if(m){inherit('materialId',m.id);inherit('nodeId',m.nodeId);}};
 const supply=id=>{const s=(p.supplies||[]).find(r=>r.id===id);if(s){inherit('supplyId',s.id);material(s.materialId);}};
 const receipt=id=>{const r=(p.receipts||[]).find(r=>r.id===id);if(r){inherit('receiptId',r.id);supply(r.supplyId);}};
 if(changed('nodeId'))clear(recordKeys);
 if(changed('materialId')){clear(['supplyId','receiptId','reshipId']);material(explicit.materialId);}
 if(changed('supplyId')){clear(['receiptId','reshipId']);supply(explicit.supplyId);}
 if(changed('receiptId')){clear(['reshipId']);receipt(explicit.receiptId);}
 if(changed('reshipId')){const r=(p.reships||[]).find(r=>r.id===explicit.reshipId);if(r)receipt(r.receiptId);}
 if(next.nodeId!==current.nodeId)clear(['inspectionId','rectificationId','record']);
 if(changed('inspectionId')){clear(batchKeys.concat(['rectificationId','record']));const i=(p.inspections||[]).find(r=>r.id===explicit.inspectionId);if(i)inherit('nodeId',i.nodeId);}
 if(changed('rectificationId')){clear(batchKeys.concat(['record']));const r=(p.rectifications||[]).find(r=>r.id===explicit.rectificationId),i=r&&(p.inspections||[]).find(i=>i.id===r.inspectionId);if(i){inherit('inspectionId',i.id);inherit('nodeId',i.nodeId);}}
 if(changed('collectionId'))clear(batchKeys.concat(['inspectionId','rectificationId','record']));
 if(changed('record')){clear(recordKeys.filter(k=>k!=='record'));const r=(p.siteRecords||[]).find(r=>r.id===explicit.record);if(r)inherit('nodeId',r.nodeId);}
 if(['overview','schedule','documents','materials','payments','history','delivery','archive','customer','versions','notifications','review','warranty','handbook','payment-history','collection-history','inspection-history','delivery-record'].includes(view))clear(recordKeys);
 if(view==='overview'&&!has('nodeId'))delete next.nodeId;
 return next;
}
module.exports=resolve;
