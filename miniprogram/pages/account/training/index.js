const api = require('../../../api/training');
const http = require('../../../api/http');
const auth = require('../../../api/auth');
const O = require('../../../utils/organization');
const fallback = '/assets/account-course.jpg';
const message = e => e && e.message || '加载失败，请稍后重试';
const denied = error => error?.postInvalid || [401,403,404,1103000002].includes(Number(error?.code));
function identity() {
  const post = http.token() && auth.currentApiPost();
  return post ? [api.identity(),post.userId,post.orgId,post.orgType,post.roleCode].join('|') : '';
}
const duration = n => { const s = Math.round(Number(n) || 0); return Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0'); };
Page({
 data: { statusHeight: 20, title: '学习中心', loading: false, error: '', keyword: '', tab: '课程', category: '全部', categories: ['全部'], courses: [], visibleCourses: [], resources: [], visibleResources: [], course: null, resumeCourse: null, activeLesson: null, courseTab: '目录', mediaUrl: '', document: false, busy: false, roleLabel: '', readAllowed: false },
 onLoad(options) { this.courseId = options.id || ''; this.catalogTitle = options.entry === 'academy' ? '商学院' : '学习中心'; this.setData({ title: this.courseId ? '课程详情' : this.catalogTitle, courseId: this.courseId, tab: options.tab === '资料' ? '资料' : '课程', statusHeight: (getApp().globalData.windowInfo || wx.getSystemInfoSync()).statusBarHeight || 20 }); },
 onShow() {
   this.hidden=false;
   this.syncIdentity();
   if (!http.token()) {
     const url='/pages/account/training/index'+(this.courseId?'?id='+encodeURIComponent(this.courseId):this.catalogTitle==='商学院'?'?entry=academy':'');
     wx.redirectTo({url:'/pages/account/index?view=login&return='+encodeURIComponent(url)});
     return;
   }
   if (!this.actorKey) {
     this.clearProtected('请先选择有效岗位后访问学习中心');
     return;
   }
   this.refresh();
 },
 syncIdentity() {
   const actor=identity();
   if (actor!==this.actorKey) { this.actorKey=actor; this.clearProtected(''); }
   return !!actor;
 },
 clearProtected(error) {
   this.accessVersion=(this.accessVersion||0)+1;
   this.generation=(this.generation||0)+1;
   this.stopPlayback();
   this.setData({courses:[],resources:[],visibleCourses:[],visibleResources:[],coursesEmpty:true,resourcesEmpty:true,
     resumeCourse:null,activeLesson:null,course:null,categories:['全部'],category:'全部',loading:false,error});
 },
 current(version) { return !this.closed && this.syncIdentity() && version===this.accessVersion; },
 alive(gen) { return !this.closed && this.syncIdentity() && gen===this.generation; },
 async refresh() {
   if (!this.syncIdentity() || this.closed) return;
   const gen=this.generation=(this.generation||0)+1, post=auth.currentApiPost();
   this.setData({loading:true,error:'',roleLabel:post ? O.roles[post.roleCode]||post.roleCode : ''});
   try {
     if (this.courseId) {
       const c=await api.course(this.courseId);
       if (!this.alive(gen)) return;
       c.lessons=(c.lessons||[]).map(l=>({...l,durationText:l.duration?duration(l.duration):'讲义'}));
       const attached={};
       [...(c.attachments||[]),...c.lessons.flatMap(l=>l.attachments||[])].forEach(a=>{
         attached[a.versionId]={...a,download:a.download||!!attached[a.versionId]?.download};
       });
       c.allAttachments=Object.values(attached);
       const previous=this.data.activeLesson, active=c.lessons.find(l=>l.id===previous?.id);
       if (this.data.course && (c.releaseId!==this.data.course.releaseId || (previous && !active))) {
         this.accessVersion++;
         this.stopPlayback();
       }
       this.setData({course:c,title:'课程详情',activeLesson:active||c.lessons.find(l=>!l.completed)||c.lessons[0]||null});
     }
     else {
       const requests=[api.courses(''),api.materials('')].map(request=>request.catch(error=>{
         if (denied(error)&&this.alive(gen))this.clearProtected(message(error));
         throw error;
       }));
       const result=await Promise.allSettled(requests);
       if (!this.alive(gen)) return;
       const failed=result.find(r=>r.status==='rejected');
       if (failed)throw failed.reason;
       const courses=result[0].value.map(c=>({...c,image:fallback}));
       const preferred=courses.find(c=>c.completed>0&&c.percent<100)||courses[0]||null;
       const resources=result[1].value.map(r=>({...r,rowKey:r.courseId+':'+r.versionId}));
       this.setData({courses,resources,categories:['全部',...new Set(courses.map(c=>c.category))],resumeCourse:preferred,title:this.catalogTitle});
       this.filter();
       for (const c of courses) if (c.coverVersionId) {
         api.ticket(c.id,c.coverVersionId,'poster').then(t=>{
           if(!this.alive(gen))return;
           c.image=api.url(t);
           this.setData({courses:this.data.courses.map(r=>r.id===c.id?c:r),resumeCourse:this.data.resumeCourse?.id===c.id?c:this.data.resumeCourse});
           this.filter();
         }).catch(()=>{});
       }
     }
  } catch (e) {
    if (this.alive(gen)) {
      if (denied(e)) this.clearProtected(message(e));
      else this.setData({error:message(e)});
    }
  } finally { if (this.alive(gen)) this.setData({loading:false}); }
 },
 filter() {
  const keyword = this.data.keyword.trim(), category = this.data.category;
  const matches = row => category === '全部' || row.category === category;
  const visibleCourses = this.data.courses.filter(c => matches(c) && (c.title + c.summary).includes(keyword));
  const visibleResources = this.data.resources.filter(r => matches(r) && (r.name + r.fileName).includes(keyword));
  this.setData({ visibleCourses, visibleResources, coursesEmpty: visibleCourses.length === 0, resourcesEmpty: visibleResources.length === 0 });
 },
 search(e) { this.setData({ keyword: e.detail.value }); this.filter(); },
 tab(e) { this.setData({ tab: e.currentTarget.dataset.value }); },
 category(e) { this.setData({ category: e.currentTarget.dataset.value }); this.filter(); },
 courseTab(e) { this.setData({ courseTab: e.currentTarget.dataset.value }); },
 openCourse(e) { wx.navigateTo({ url: '/pages/account/training/index?id=' + encodeURIComponent(e.currentTarget.dataset.id) }); },
 async selectLesson(e) {
   const id=e.currentTarget.dataset.id, lesson=this.data.course?.lessons?.find(l=>l.id===id);
   const version=this.accessVersion;
   if (!lesson||this.data.busy||!this.current(version)) return;
   await this.flush();
   if (!this.current(version)) return;
   this.stopPlayback(); this.setData({activeLesson:lesson}); await this.startLesson();
 },
 async actionError(error, version, fallbackMessage) {
   if (!this.current(version)) return;
   if (Number(error?.code)===400 && /学习会话已失效|课件已更新/.test(error.message||'')) {
     this.stopPlayback();
     await this.refresh();
     if (this.current(version)&&!this.data.error)this.setData({error:message(error)});
     return;
   }
   if (!denied(error)) { this.setData({error:fallbackMessage||message(error)}); return; }
   this.clearProtected(message(error));
   // A file or play ticket can expire while the course itself remains authorized.
   if ([403,404].includes(Number(error.code)) && !error.postInvalid) {
     const next=this.accessVersion;
     await this.refresh();
     if (this.current(next) && !this.data.error) this.setData({error:message(error)});
   }
 },
 async startLesson() {
   const lesson=this.data.activeLesson, version=this.accessVersion;
   if (!lesson||this.data.busy||!this.current(version)) return;
   const seq=this.playSequence=(this.playSequence||0)+1;
   const active=()=>this.current(version)&&seq===this.playSequence;
   this.setData({busy:true,error:''});
   try {
     const t=await api.play(this.courseId, lesson.id);
     if (!active()||this.hidden) return;
     this.playId=t.playId; this.position=Number(t.position)||0;
     const audio=['mp3','m4a'].includes(t.extension);
     this.setData({mediaUrl:api.url(t),document:t.document,startPosition:this.position,readAllowed:false},()=>{
       if (!t.document&&!audio) wx.nextTick(()=>{if(active())wx.createVideoContext('training-video',this).play();});
     });
     if (!t.document && audio) this.startAudio(api.url(t),this.position,seq);
     if (t.document) {
       if (['jpg','jpeg','png','webp'].includes(t.extension)) wx.previewImage({urls:[api.url(t)],current:api.url(t)});
       else await this.openFile(api.url(t),'pdf',false,active);
       if (active()) this.readTimer=setTimeout(()=>{if(active())this.setData({readAllowed:true});},30000);
     }
   } catch(e) { if(active()) await this.actionError(e,version); }
   finally { if(active())this.setData({busy:false}); }
 },
 startAudio(url,position,seq) {
   const audio=this.audio=wx.createInnerAudioContext(), version=this.accessVersion;
   const active=()=>this.current(version)&&seq===this.playSequence;
   audio.src=url; audio.startTime=position; audio.autoplay=true;
   audio.onCanplay(()=>{if(active())audio.play();});
   audio.onPlay(()=>{if(active())this.setData({audioPlaying:true});});
   const paused=()=>{if(active()){this.flush();this.setData({audioPlaying:false});}};
   audio.onPause(paused); audio.onEnded(paused);
   audio.onTimeUpdate(()=>{if(active()){this.setData({audioPosition:duration(audio.currentTime)});this.timeupdate({detail:{currentTime:audio.currentTime}});}});
   audio.onError(()=>{if(active())this.playbackError();});
 },
 toggleAudio() { if (this.audio) this.audio.paused ? this.audio.play() : this.audio.pause(); },
 timeupdate(e) { this.position = Number(e.detail.currentTime) || 0; if (!this.lastBeat || Date.now() - this.lastBeat >= 5000) { this.lastBeat = Date.now(); this.flush(); } },
 async flush() {
   const play=this.playId, version=this.accessVersion;
   if (!play||this.data.document||!this.current(version)) return;
   if (this.beat?.play===play) return this.beat.promise;
   const beat={play}; this.beat=beat;
   const current=()=>this.current(version)&&play===this.playId&&this.beat===beat;
   beat.promise=(async()=>{
     try {
       const result=await api.beat(play,this.position||0);
       if(current()&&result?.completed===true)this.markCompleted();
     } catch(e) {
       if(current())await this.actionError(e,version,'学习进度暂未保存，请检查网络后继续播放');
     } finally { if(this.beat===beat)this.beat=null; }
   })();
   return beat.promise;
 },
 paused() { this.flush(); }, ended() { this.flush(); },
 markCompleted() { const c = this.data.course, id = (this.data.activeLesson || {}).id; if (!c) return; c.lessons = c.lessons.map(l => l.id === id ? { ...l, completed: true } : l); c.completed = c.lessons.filter(l => l.completed).length; c.percent = Math.round(100 * c.completed / c.lessons.length); this.setData({ course: c, activeLesson: c.lessons.find(l => l.id === id) }); },
 async confirmRead() {
   const play=this.playId, version=this.accessVersion, seq=this.playSequence;
   if (!play||!this.data.readAllowed||this.data.activeLesson?.completed||this.data.busy||!this.current(version)) return;
   const current=()=>this.current(version)&&play===this.playId&&seq===this.playSequence;
   this.setData({busy:true,error:''});
   try {
     const result=await api.read(play);
     if (!current())return;
     if (result?.completed!==true) { this.setData({error:'服务端尚未确认已读，请重试'}); return; }
     this.markCompleted(); if(!this.hidden)wx.showToast({title:'已记录阅读',icon:'success'});
   } catch(e) { if(current())await this.actionError(e,version); }
   finally { if(current())this.setData({busy:false}); }
 },
 async resource(e) {
   const d=e.currentTarget.dataset, version=this.accessVersion;
   const active=()=>{
     if (!this.current(version))return false;
     const listed=this.courseId?this.data.course?.allAttachments:this.data.resources;
     return listed?.some(r=>r.versionId===d.id&&(!d.course||(r.courseId||this.courseId)===d.course)&&(d.download?r.download:r.preview));
   };
   if (this.data.busy||!active())return;
   this.setData({busy:true,error:''});
   try {
     const t=await api.ticket(d.course||this.courseId,d.id,d.download?'download':'preview');
     if(!active()||this.hidden)return;
     const url=api.url(t);
     if (!d.download&&['jpg','jpeg','png','webp'].includes(d.ext)) wx.previewImage({urls:[url],current:url});
     else await this.openFile(url,d.download?d.ext:'pdf',!!d.download,active);
   } catch(e) { if(active())await this.actionError(e,version); }
   finally { if(this.current(version))this.setData({busy:false}); }
 },
 openFile(url,type,download,active) {
   return new Promise((resolve,reject)=>wx.downloadFile({url,success:r=>{
     if(!active()||this.hidden)return resolve();
     if(r.statusCode!==200)return reject({code:r.statusCode,message:'资料链接已失效，请重新打开'});
     if(download&&!['pdf','docx','xlsx','pptx'].includes(type)){
       if(wx.shareFileMessage)wx.shareFileMessage({filePath:r.tempFilePath,success:resolve,fail:reject});
       else reject({message:'当前微信版本不支持此文件下载，请升级微信'});
     }else wx.openDocument({filePath:r.tempFilePath,fileType:type,showMenu:download,success:resolve,fail:reject});
   },fail:()=>reject({message:'资料下载失败，请重试'})}));
 },
 playbackError() { this.setData({ error: '课件播放失败或链接已过期，请重新打开本节' }); this.stopPlayback(); },
 stopPlayback() { this.playSequence = (this.playSequence || 0) + 1; clearTimeout(this.readTimer); this.playId = ''; this.beat = null; if (this.audio) { this.audio.destroy(); this.audio = null; } this.position = 0; this.lastBeat = 0; try { wx.createVideoContext('training-video', this).pause(); } catch (e) {} this.setData({ mediaUrl: '', document:false, audioPlaying:false, audioPosition:'', startPosition:0, busy: false, readAllowed: false }); },
 onHide() { this.hidden=true; this.flush(); if (!this.data.document) this.stopPlayback(); },
 onUnload() { this.flush(); this.stopPlayback(); this.closed = true; this.generation = (this.generation || 0) + 1; }
});
