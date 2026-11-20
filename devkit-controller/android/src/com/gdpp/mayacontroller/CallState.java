package kr.silvertalk.devkit;

/** A missing foreground window is not an acknowledgement that a call ended. */
final class CallState {
  private final boolean[] active=new boolean[VoiceService.COUNT+1];
  private final long[] since={-1,-1,-1,-1,-1};
  private final long[] idleSince={-1,-1,-1,-1,-1};
  void ended(int service){if(VoiceService.valid(service)){active[service]=false;since[service]=-1;idleSince[service]=-1;}}
  boolean anyActive(){
    for(int service=1;service<=VoiceService.COUNT;service++)if(active[service])return true;
    return false;
  }
  boolean active(int service){return VoiceService.valid(service)&&active[service];}
  boolean observe(int service,AppSequence.Screen screen,long now){
    for(int other=1;other<=VoiceService.COUNT;other++)if(other!=service)since[other]=-1;
    if(!VoiceService.valid(service))return false;
    if(screen==AppSequence.Screen.BLOCKED)ended(service);
    else if(screen==AppSequence.Screen.IDLE){
      since[service]=-1;
      if(idleSince[service]<0)idleSince[service]=now;
      if(now-idleSince[service]>=600)active[service]=false;
    }
    else if(screen==AppSequence.Screen.ACTIVE){
      idleSince[service]=-1;
      if(since[service]<0)since[service]=now;
      if(service==1||now-since[service]>=2000)active[service]=true;
    }else{since[service]=-1;idleSince[service]=-1;}
    return active[service]&&screen==AppSequence.Screen.ACTIVE;
  }
}


