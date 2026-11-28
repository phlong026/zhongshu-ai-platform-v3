// Contract cash corrections keep the original receipt and use the existing durable request recovery.
module.exports = function attach(page, api, PF, http, auth) {
  const originalOpen=page.openRevision, originalSubmit=page.submitRevision, originalInput=page.revisionInput;
  const finance=()=>['finance','finance-lead'].includes(auth.currentApiPost()?.roleCode);
  page.openRevision=function(e) {
    if(this.data.registerBusy||this.data.revisionBusy||this.data.paymentPending)return;
    const key=String(e.currentTarget.dataset.id||'');
    const row=(this.__journeyCash||[]).find(r=>r.key===key)||(this.data.platformLedger||[]).find(r=>String(r.id)===key)||(this.ledgerSource||[]).find(r=>String(r.id)===key);
    const receiptId=key.replace(/^r-/,'');
    const receipt=(this.__financeFacts?.receipts||[]).find(r=>String(r.id)===receiptId);
    if((row?.source_type||row?.sourceType||receipt?.sourceType)!=='business')return originalOpen.call(this,e);
    if(!finance()||!/^\d+$/.test(receiptId))return;
    const session=http.sessionGuard(), attempt=this.__receiptCorrection={};
    const alive=()=>session()&&finance()&&!this.__disposed&&!this.__financeHidden&&this.__receiptCorrection===attempt&&this.data.registerType==='revision';
    this.__receiptCorrectionAlive=alive;
    this.setData({registerType:'revision',revisionBusy:false,bookEntryDetail:null,revisionForm:{contract:true,loading:true,receiptId},registerError:'',revisionHistory:{status:'loading',rows:[]}});
    return api.contractReceiptCorrection(receiptId).then(detail=>{
      if(!alive())return;
      const stages=detail.stages||[],selected=stages.find(s=>String(s.stageId)===String(detail.stageId));
      const rows=(detail.history||[]).map(h=>({versionAfter:h.revision,originalText:PF.yuan(h.before.amountCents),newText:PF.yuan(h.after.amountCents),
        note:h.request?.change?.reason||'',dateText:String(h.createdAt||'').slice(0,16).replace('T',' '),
        changeText:(h.before.receivedDate||'未填日期')+' → '+h.after.receivedDate+'；期次 '+(stages.find(s=>String(s.stageId)===String(h.before.stageId))?.label||h.before.stageId)+' → '+(stages.find(s=>String(s.stageId)===String(h.after.stageId))?.label||h.after.stageId)}));
      this.setData({revisionForm:{contract:true,loading:false,receiptId,baseRevisionVersion:detail.revisionVersion,originAmount:PF.yuan(detail.amountCents),amount:PF.yuan(detail.amountCents),
        receivedDate:detail.receivedDate||'',stageId:String(detail.stageId),stageLabel:selected?.label||'',stages,note:''},revisionHistory:{status:rows.length?'ready':'empty',rows}});
    }).catch(error=>{if(alive())this.setData({registerError:PF.errorMessage(error),'revisionHistory.status':'failed','revisionForm.loading':false,'revisionForm.failed':true});});
  };
  page.revisionInput=function(e) {
    if(this.data.paymentPending||this.data.registerBusy)return;
    if(!this.data.revisionForm?.contract)return originalInput.call(this,e);
    if(!this.__receiptCorrectionAlive?.())return;
    if(e.currentTarget.dataset.field==='stageId') {
      const stage=this.data.revisionForm.stages[Number(e.detail.value)];
      if(stage)this.setData({'revisionForm.stageId':String(stage.stageId),'revisionForm.stageLabel':stage.label});
      return;
    }
    return originalInput.call(this,e);
  };
  page.submitRevision=function() {
    const form=this.data.revisionForm;
    if(!form?.contract)return originalSubmit.call(this);
    if(!finance()||!this.__receiptCorrectionAlive?.()||this.data.paymentPending||this.data.registerBusy||form.loading||form.failed)return;
    const amountCents=PF.toCents(form.amount);
    if(!amountCents)return this.setData({registerError:'请输入正确的新金额'});
    if(!form.receivedDate||form.receivedDate>require('../../../utils/account').localDate())return this.setData({registerError:'请选择真实收款日期，不能晚于今天'});
    if(!form.stageId)return this.setData({registerError:'请选择原合同期次'});
    if(!String(form.note||'').trim()||String(form.note).trim().length>255)return this.setData({registerError:'请填写更正原因，最多255字'});
    this.setData({registerError:''});
    return this.dispatchPreparedPayment({receiptId:form.receiptId,baseRevisionVersion:form.baseRevisionVersion,amountCents,
      receivedDate:form.receivedDate,stageId:form.stageId,reason:form.note.trim()},api.idemKey(),form,'receipt-correction');
  };
};
