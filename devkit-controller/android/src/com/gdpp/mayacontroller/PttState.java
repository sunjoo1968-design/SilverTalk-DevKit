package kr.silvertalk.devkit;

/** Only a fresh press in a settled live call may open its microphone. */
final class PttState {
  private boolean enabled=false,held=false,open=false,blocked=false,mode=false;
  private boolean carryMode, continuous;
  private int service=0;private boolean live=false,busy=false;
  private long received=0,epoch=0;
  synchronized void enable(){if(!enabled)blocked=true;enabled=true;}
  synchronized void input(boolean down,long now){
    received=now;
    if(down&&!held){open=enabled&&live&&!busy&&!blocked;if(open)mode=true;epoch++;}
    if(!down){open=false;blocked=false;if(held)epoch++;}
    held=down;
  }
  synchronized void context(int app,boolean active,boolean transition){
    if(carryMode){
      if(!transition){
        if(!active)mode=false;
        carryMode=false;
      }
    }else if(app!=service||(!active&&live))mode=false;
    if(app!=service||active!=live||transition!=busy){open=false;continuous=false;blocked=held;epoch++;}
    service=app;live=active;busy=transition; mode=enabled&&active;
  }
  synchronized void cancel(){continuous=false;open=false;blocked=held;epoch++;}
  synchronized void switchCall(){carryMode=mode;cancel();}
  synchronized void normal(){continuous=false;carryMode=false;mode=false;open=false;blocked=held;epoch++;}
  synchronized boolean mode(){return mode;}
  synchronized void disconnected(){continuous=false;open=false;held=false;blocked=true;epoch++;}
  synchronized boolean enabled(){return enabled;}
  synchronized boolean muted(long now){
    if(held&&now-received>750){open=false;blocked=true;epoch++;held=false;}
    return mode&&(!(open||continuous)||!live||busy);
  }
  synchronized boolean continuous(){return continuous;}
  synchronized boolean beginContinuous(){
    if(!enabled || !live || busy)return false;
    continuous=true;
    epoch++;
    return true;
  }
  synchronized long epoch(){return epoch;}
  synchronized boolean held(){return held;}
  synchronized boolean permits(int app,boolean mute,long version,long now){return enabled&&service==app&&epoch==version&&muted(now)==mute;}
}



