function time(seconds) {
  const value=Math.max(0,Math.floor(Number(seconds)||0));
  return String(Math.floor(value/60)).padStart(2,'0')+':'+String(value%60).padStart(2,'0');
}
function create({createAudio,valid,onChange}) {
  let player=null,key='',url='',playing=false,ended=false;
  const emit=patch=>{if(valid())onChange(patch);};
  const reset=()=>{const old=player;player=null;key='';url='';playing=false;ended=false;if(old)old.destroy();};
  function toggle(nextKey,nextUrl) {
    if(!valid()||!nextUrl)return;
    if(player&&key===nextKey&&url===nextUrl){if(playing)player.pause();else{emit({previewAudioLoading:true,previewAudioError:''});if(ended){player.seek(0);ended=false;}player.play();}return;}
    reset();key=nextKey;url=nextUrl;const audio=player=createAudio();
    const active=()=>player===audio&&valid();
    const progress=()=>{if(!active())return;const position=Number(audio.currentTime)||0,duration=Number(audio.duration)||0;
      emit({previewAudioPosition:time(position),previewAudioDuration:time(duration),previewAudioProgress:duration?Math.min(100,position/duration*100):0});};
    emit({previewAudioId:key,previewAudioPlaying:false,previewAudioLoading:true,previewAudioError:'',previewAudioPosition:'00:00',previewAudioDuration:'00:00',previewAudioProgress:0});
    audio.onCanplay(()=>{if(active()){progress();emit({previewAudioLoading:false});}});
    audio.onPlay(()=>{if(active()){playing=true;emit({previewAudioPlaying:true,previewAudioLoading:false});}});
    audio.onPause(()=>{if(active()){playing=false;emit({previewAudioPlaying:false,previewAudioLoading:false});}});
    audio.onEnded(()=>{if(active()){playing=false;ended=true;progress();emit({previewAudioPlaying:false,previewAudioLoading:false});}});
    audio.onTimeUpdate(progress);
    audio.onError(()=>{if(active()){playing=false;emit({previewAudioPlaying:false,previewAudioLoading:false,previewAudioError:'音频暂时无法播放，请重新选择或稍后重试'});}});
    audio.autoplay=false;audio.src=nextUrl;audio.play();
  }
  return {toggle,pause(){if(player)player.pause();},destroy:reset,
    seek(id,percent){if(!valid()||!player||key!==id||!Number(player.duration))return;player.seek(Number(player.duration)*Math.max(0,Math.min(100,Number(percent)||0))/100);}};
}
module.exports={create,time};
