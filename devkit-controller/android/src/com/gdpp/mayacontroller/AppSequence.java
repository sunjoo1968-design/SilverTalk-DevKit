package kr.silvertalk.devkit;

/** UI acknowledgements, not successful intent dispatch, advance each stage. */
final class AppSequence {
  enum Screen { UNKNOWN, IDLE, CONNECTING, ACTIVE, BLOCKED, AMBIGUOUS }
  enum Action { NONE, OPEN, START, STOP }
  private enum Phase { NONE, SOURCE, STOPPING, TARGET, STARTING }
  private Phase phase=Phase.NONE;
  private int selected=1,working=1,target=1;
  private boolean switching=false,resume=false,sent=false,opened=false,redirect=false;
  private boolean stopRequested=false,keepActive=false,prepareOnly=false;
  private long deadline=0,notBefore=0,lastOpenAt=0;
  private long activeSince=-1;
  private long ambiguousSince=-1;
  String error="";
  int completed=0;
  AppSequence(int initial){selected=working=target=VoiceService.valid(initial)?initial:VoiceService.SESAME;}
  int selected(){return selected;}
  int working(){return working;}
  int target(){return target;}
  boolean busy(){return phase!=Phase.NONE;}
  boolean stopping(){return phase==Phase.STOPPING;}
  boolean starting(){return phase==Phase.STARTING||(phase==Phase.TARGET&&resume);}
  boolean expire(long now){if(busy()&&now>=deadline){fail("대화 상태 확인 시간 초과 · 현재 앱을 확인하세요");return true;}return false;}
  boolean select(int service,long now){
    return select(service,now,false);
  }
  boolean select(int service,long now,boolean wasActive){
    if(!VoiceService.valid(service))return false;
    if(busy()){
      target=service;switching=true;
      if(phase==Phase.STARTING){
        resume=true;
        if(sent)redirect=true; // A dispatched start may connect late: stop it before leaving.
        else{phase=Phase.TARGET;opened=false;}
      }
      return true;
    }
    begin(true,service,now);resume=wasActive;return true;
  }
  boolean toggle(long now){return toggle(now,false);}
  boolean toggle(long now,boolean wasActive){
    if(busy())return false;
    begin(false,selected,now);stopRequested=wasActive;return true;
  }
  void selectIdle(int service){
    selected=working=target=service;
    error="";
  }
  void selectForReady(int service,long now){
    begin(true,service,now);
    prepareOnly=true;
  }
  boolean startOrKeep(long now){
    if(busy())return false;
    begin(false,selected,now);
    keepActive=true;
    return true;
  }
  private void begin(boolean isSwitch,int service,long now){
    switching=isSwitch;resume=false;sent=false;opened=false;redirect=false;stopRequested=false;keepActive=false;prepareOnly=false;error="";
    working=selected;target=service;phase=Phase.SOURCE;deadline=now+45000;notBefore=now;activeSince=-1;ambiguousSince=-1;
  }
  void fail(String message){phase=Phase.NONE;error=message;sent=false;redirect=false;}
  private void finish(){phase=Phase.NONE;sent=false;redirect=false;completed++;}
  private void target(long now){phase=Phase.TARGET;sent=false;opened=false;activeSince=-1;notBefore=now+500;deadline=now+45000;}
  private Action observe(int visibleService,long now){
    if(visibleService==working)return Action.NONE;
    // An older asynchronous app launch can arrive after the latest one.
    // Reassert the required app without flooding Android with launches.
    if(!opened||now-lastOpenAt>=1500){opened=true;lastOpenAt=now;return Action.OPEN;}
    return Action.NONE;
  }
  Action step(int visibleService,Screen screen,long now){
    if(!busy())return Action.NONE;
    if(expire(now))return Action.NONE;
    if(phase==Phase.TARGET&&working!=target){working=selected=target;opened=false;sent=false;}
    if(visibleService!=working)return observe(visibleService,now);
    if(screen==Screen.AMBIGUOUS){
      if(ambiguousSince<0)ambiguousSince=now;
      if(now-ambiguousSince>=800)fail("음성 버튼이 중복되어 명령을 중단했어요");
      return Action.NONE;
    }
    ambiguousSince=-1;
    if(screen==Screen.UNKNOWN)return Action.NONE;
    if(phase==Phase.SOURCE){
      if(screen==Screen.BLOCKED&&!switching){fail("선택 앱 음성 한도/오류 안내를 확인하세요");return Action.NONE;}
      if(keepActive && (screen==Screen.ACTIVE||screen==Screen.CONNECTING)){
        phase=Phase.STARTING;sent=(screen==Screen.ACTIVE);deadline=now+35000;
      }else if(screen==Screen.ACTIVE||screen==Screen.CONNECTING||screen==Screen.BLOCKED){
        resume=!prepareOnly&&switching&&(resume||screen!=Screen.BLOCKED);
        phase=Phase.STOPPING;sent=false;opened=false;deadline=now+35000;
      }else if(switching){target(now);return Action.NONE;}
      else if(stopRequested){finish();return Action.NONE;}
      else{phase=Phase.STARTING;sent=false;deadline=now+35000;}
    }
    if(phase==Phase.STOPPING){
      if(screen==Screen.IDLE){
        if(switching){target(now);return Action.NONE;}
        finish();return Action.NONE;
      }
      if(!sent){sent=true;return Action.STOP;}
      return Action.NONE;
    }
    if(phase==Phase.TARGET){
      if(screen==Screen.BLOCKED){fail("선택 앱 음성 한도/오류로 자동 연결을 중단했어요");return Action.NONE;}
      if(screen==Screen.ACTIVE){finish();return Action.NONE;}
      if(screen!=Screen.IDLE)return Action.NONE;
      if(!resume){finish();return Action.NONE;}
      phase=Phase.STARTING;sent=false;deadline=now+35000;
    }
    if(phase==Phase.STARTING){
      if(screen!=Screen.ACTIVE)activeSince=-1;
      if(screen==Screen.BLOCKED){
        if(redirect){phase=Phase.STOPPING;sent=true;opened=false;redirect=false;deadline=now+35000;return Action.STOP;}
        fail("선택 앱 음성 한도/오류로 연결하지 못했어요");return Action.NONE;
      }
      if(screen==Screen.ACTIVE){
        if(redirect){phase=Phase.STOPPING;sent=false;opened=false;redirect=false;resume=true;deadline=now+35000;return Action.NONE;}
        // Gemini Live briefly exposes End before a quota error. Require stable UI before LED ON.
        if(working!=VoiceService.SESAME){if(activeSince<0)activeSince=now;if(now-activeSince<2000)return Action.NONE;}
        finish();return Action.NONE;
      }
      if(screen==Screen.IDLE&&!sent&&now>=notBefore){sent=true;return Action.START;}
    }
    return Action.NONE;
  }
  String status(){
    if(!error.isEmpty())return error;
    if(phase==Phase.STOPPING)return "현재 대화 종료 확인 중…";
    if(phase==Phase.STARTING)return "선택 앱 음성 연결 확인 중…";
    if(phase==Phase.TARGET)return resume?"선택 앱으로 이동 후 자동 연결 중…":"선택 앱 화면 확인 중…";
    return busy()?"현재 앱의 대화 상태 확인 중…":"";
  }
}





