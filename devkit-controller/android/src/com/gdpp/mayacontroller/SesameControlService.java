package kr.silvertalk.devkit;

import android.accessibilityservice.*;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.pm.ServiceInfo;
import android.graphics.Path;
import android.graphics.Rect;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.util.Log;
import android.view.accessibility.*;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Component name is retained to preserve the existing accessibility grant. */
public final class SesameControlService extends AccessibilityService {
  private static final String[] TITLES=VoiceService.TITLES;
  static SesameControlService current;
  private final Handler handler=new Handler(Looper.getMainLooper());
  private AppSequence sequence;
  private final CallState calls=new CallState();
  private final PttState ptt=new PttState();
  private long lastMicAttempt=0;
  private long inputGeneration=0;
  private volatile ControllerLink midi;
  private volatile boolean running=false;
  private boolean foregroundActive=false;
  private PowerManager.WakeLock usbWakeLock;
  private HandlerThread midiThread;
  private Handler midiHandler;
  private UiLane uiLane;
  private ResilientLoop heartbeat;
  private volatile long ioTicks=0,receivedInputs=0;
  private long jobStartedAt=0,observerMillis=0,inputLagMillis=0;
  private boolean actionPending=false,quotaDismissAttempted=false,geminiRejected=false;
  private static final class Feedback {
    final int selected,led,mode;
    Feedback(int selected,int led,int mode){this.selected=selected;this.led=led;this.mode=mode;}
  }
  private volatile Feedback feedback=new Feedback(1,0,0);
  private final int[] counts={0,0,0};
  private volatile String usb="Pro Micro 연결 대기";
  private String notice="",lastAction="",lastSaved="";
  private int cLed=0;
  private Ui ui=new Ui();
  private final ArrayDeque<String> history=new ArrayDeque<>();
  private static final class Ui {
    int service=0;
    AppSequence.Screen screen=AppSequence.Screen.UNKNOWN;
    String button="",agent="",controls="";
    boolean incomplete=false;
    int mic=-1; // -1 unknown, 0 open, 1 muted
    boolean geminiHold;
  }
  private static final class Scan implements AutoCloseable {
    final Ui ui=new Ui();
    final List<AccessibilityNodeInfo> starts=new ArrayList<>(),ends=new ArrayList<>(),genericEnds=new ArrayList<>(),mics=new ArrayList<>(),dismiss=new ArrayList<>();
    final List<AccessibilityNodeInfo> muteButtons=new ArrayList<>(),unmuteButtons=new ArrayList<>();
    final List<AccessibilityNodeInfo> holdButtons=new ArrayList<>(),resumeButtons=new ArrayList<>();
    void mic(AccessibilityNodeInfo node,String label){
      boolean gemini=ui.service==VoiceService.GEMINI;
      int toggleState=gemini?VoiceLabels.geminiToggleState(label,node.isCheckable(),node.isChecked()):-1;
      List<AccessibilityNodeInfo> list;
      if(toggleState==0||VoiceLabels.muteAction(label)||(gemini&&VoiceLabels.geminiMute(label,true)))list=muteButtons;
      else if(toggleState==1||VoiceLabels.unmuteAction(label)||(gemini&&VoiceLabels.geminiMute(label,false)))list=unmuteButtons;
      else if(gemini&&VoiceLabels.geminiHold(label))list=holdButtons;
      else if(gemini&&VoiceLabels.geminiResume(label))list=resumeButtons;
      else list=null;
      if(list==null)return;
      AccessibilityNodeInfo candidate=clickable(node);if(candidate==null)candidate=AccessibilityNodeInfo.obtain(node);
      for(AccessibilityNodeInfo old:list)if(old.equals(candidate)){candidate.recycle();return;}
      list.add(candidate);
    }
    void classifyGeminiControls(){
      int endCount=ends.isEmpty()?genericEnds.size():ends.size();
      if(endCount!=1||limit||ui.incomplete||!muteButtons.isEmpty()||!unmuteButtons.isEmpty())return;
      List<AccessibilityNodeInfo> resume=resumeButtons.isEmpty()?starts:resumeButtons;
      if(holdButtons.size()==1&&resumeButtons.isEmpty()){
        ui.geminiHold=true;
        ui.mic=0;
      }else if(holdButtons.isEmpty()&&resume.size()==1){
        ui.geminiHold=true;
        ui.mic=1;
      }
    }
    List<AccessibilityNodeInfo> micTargets(boolean mute){
      if(!ui.geminiHold)return mute?muteButtons:unmuteButtons;
      if(mute)return holdButtons;
      return resumeButtons.isEmpty()?starts:resumeButtons;
    }
    boolean limit=false,connecting=false;
    void add(List<AccessibilityNodeInfo> list,AccessibilityNodeInfo node){
      AccessibilityNodeInfo candidate=clickable(node);if(candidate==null)return;
      for(AccessibilityNodeInfo old:list)if(old.equals(candidate)){candidate.recycle();return;}list.add(candidate);
    }
    private static AccessibilityNodeInfo clickable(AccessibilityNodeInfo node){
      AccessibilityNodeInfo n=AccessibilityNodeInfo.obtain(node);
      for(int depth=0;n!=null&&depth<6;depth++){
        if(n.isVisibleToUser()&&n.isEnabled()&&n.isClickable())return n;
        AccessibilityNodeInfo parent=n.getParent();n.recycle();n=parent;
      }
      if(n!=null)n.recycle();return null;
    }
    @Override public void close(){for(List<AccessibilityNodeInfo> list:Arrays.asList(starts,ends,genericEnds,mics,dismiss,muteButtons,unmuteButtons,holdButtons,resumeButtons))for(AccessibilityNodeInfo node:list)node.recycle();}
  }
  @Override protected void onServiceConnected(){
    super.onServiceConnected();current=this;running=true;
    assignments=getSharedPreferences(ControllerLink.SETTINGS,0).getInt("assignments",VoiceService.DEFAULT_ASSIGNMENTS);
    int prior=getSharedPreferences("native-abc",0).getInt("selected",1);
    physicalSlot=getSharedPreferences(ControllerLink.SETTINGS,0).getInt("physicalSlot",prior==2?1:0);
    sequence=new AppSequence(VoiceService.slot(assignments,physicalSlot));
    PowerManager power=(PowerManager)getSystemService(POWER_SERVICE);
    if(power!=null){usbWakeLock=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"MayaController:UsbFeedback");usbWakeLock.setReferenceCounted(false);}
    requestForegroundSession();
    AccessibilityServiceInfo info=getServiceInfo();
    if(info!=null){info.packageNames=new String[]{VoiceService.PACKAGES[1],VoiceService.PACKAGES[2],VoiceService.PACKAGES[3],VoiceService.PACKAGES[4],VoiceService.GOOGLE_APP};info.flags|=AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS|AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;setServiceInfo(info);}
    uiLane=new UiLane(command->handler.post(command));
    midiThread=new HandlerThread("MayaUsbMidi");midiThread.start();
    midiHandler=new Handler(midiThread.getLooper());
    heartbeat=new ResilientLoop(()->{
      if(!running)return;
      if(midi==null)createMidi();
      if(midi!=null){Feedback state=feedback;midi.feedback(physicalSlot+1,state.led,state.mode,assignments);}
      updateUsbWakeLock(alwaysOn()&&midi!=null&&midi.connected());
      ioTicks++;
      long now=SystemClock.elapsedRealtime();
      if(now-lastCalendarRefresh>=60000){lastCalendarRefresh=now;CalendarVoice.refresh(this);}
    },(task,delay)->midiHandler.postDelayed(task,delay),
      error->Log.w("MayaNative","USB 주기 복구: "+error.getClass().getSimpleName()),125);
    midiHandler.post(heartbeat);
    log("v"+ReleaseVersion.CODE+" 접근성 연결 · 전경 연결 유지");handler.post(tick);
  }
  private long lastCalendarRefresh=-60000;
  private boolean alwaysOn(){return getSharedPreferences(ControllerLink.SETTINGS,0).getBoolean("always",true);}
  void applyAlwaysOn(){
    if(alwaysOn())requestForegroundSession();
    else{foregroundActive=false;stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
  }
  void requestForegroundSession(){
    if(foregroundActive||!alwaysOn())return;
    try{startForegroundService(new Intent(this,SesameControlService.class));}
    catch(RuntimeException e){notice="컨트롤러 앱을 열어 USB 연결 유지를 시작하세요";Log.w("MayaNative","전경 시작 대기",e);}
  }
  @Override public int onStartCommand(Intent intent,int flags,int startId){
    NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
    String channel="maya-controller-session";
    manager.createNotificationChannel(new NotificationChannel(channel,"A/B/C 연결 유지",NotificationManager.IMPORTANCE_LOW));
    PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    Notification notification=new Notification.Builder(this,channel)
      .setSmallIcon(android.R.drawable.ic_menu_manage).setContentTitle("A/B/C 컨트롤러 작동 중")
      .setContentText("USB와 Wi-Fi 박스 연결을 유지합니다")
      .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE).build();
    try{
      if(Build.VERSION.SDK_INT>=34)startForeground(16,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
      else startForeground(16,notification);
      foregroundActive=true;log("전경 연결 유지 활성화");
    }catch(RuntimeException e){foregroundActive=false;Log.e("MayaNative","전경 연결 유지 실패",e);stopSelf(startId);}
    return alwaysOn() ? START_STICKY : START_NOT_STICKY;
  }
  private synchronized void updateUsbWakeLock(boolean needed){
    if(usbWakeLock==null)return;
    needed=needed&&running;
    if(needed&&!usbWakeLock.isHeld())usbWakeLock.acquire();
    else if(!needed&&usbWakeLock.isHeld())usbWakeLock.release();
  }
  private void createMidi(){
    midi=new ControllerLink(this,new ControllerLink.Listener(){
      @Override public void press(int key){
        receivedInputs++;long receivedAt=SystemClock.uptimeMillis();
        handler.post(()->{if(running){inputLagMillis=SystemClock.uptimeMillis()-receivedAt;SesameControlService.this.press(key);}});
      }
      @Override public void longPress(){handler.post(()->enterContinuous());}
      @Override public void continuousPress(){handler.post(()->toggleContinuous());}
      @Override public void status(String message){usb=message;}
      @Override public void pttReady(){boolean first=!ptt.enabled();ptt.enable();if(first)handler.post(()->pttChanged());}
      @Override public void ptt(boolean held){boolean changed=ptt.held()!=held;ptt.input(held,SystemClock.uptimeMillis());handler.post(()->{if(changed)pttChanged();else requestScan();});}
      @Override public void disconnected(){ptt.disconnected();handler.post(()->pttChanged());}
    });
  }
  private void pttChanged(){
    if(!running||sequence==null)return;
    if(ptt.enabled()&&calls.active(sequence.selected())&&!sequence.busy()&&!actionPending&&ui.service!=sequence.selected()&&unlocked())open(sequence.selected(),"");
    requestScan();save();
  }
  private static int serviceOf(CharSequence name){return VoiceService.fromPackage(string(name));}
  @Override public void onAccessibilityEvent(AccessibilityEvent event){if(running&&serviceOf(event.getPackageName())!=0)requestScan();}
  @Override public void onInterrupt(){requestScan();}
  @Override public boolean onUnbind(Intent intent){shutdown();return super.onUnbind(intent);}
  @Override public void onDestroy(){shutdown();super.onDestroy();}
  private void shutdown(){
    running=false;handler.removeCallbacksAndMessages(null);
    updateUsbWakeLock(false);
    foregroundActive=false;stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
    if(uiLane!=null)uiLane.close();
    if(heartbeat!=null)heartbeat.stop();
    if(midiHandler!=null)midiHandler.post(()->{if(midi!=null){midi.close();midi=null;}updateUsbWakeLock(false);midiThread.quitSafely();});
    if(current==this)current=null;
  }
  private boolean unlocked(){
    KeyguardManager lock=(KeyguardManager)getSystemService(KEYGUARD_SERVICE);PowerManager power=(PowerManager)getSystemService(POWER_SERVICE);
    return power!=null&&power.isInteractive()&&(lock==null||!lock.isKeyguardLocked());
  }
  void press(int key){
    if(!running||key<0||key>2)return;
    counts[key]++;
    if(key<2 && physicalSlot!=key){
      physicalSlot=key;
      getSharedPreferences(ControllerLink.SETTINGS,0).edit().putInt("physicalSlot",key).apply();
    }
    if(key==2 && ptt.continuous()){
      leaveContinuous();
      return;
    }
    if(!unlocked()){
      if(key==2) wakeForReady();
      else deferredSelection=VoiceService.slot(assignments,key);
      return;
    }
    boolean wasActive=calls.active(sequence.selected()) ||
        (ui.service==sequence.selected() && ui.screen==AppSequence.Screen.ACTIVE);
    long now=SystemClock.uptimeMillis();
    if(key<2){
      int service=VoiceService.slot(assignments,key);
      if(service==sequence.selected()&&!sequence.busy())return;
      if(!wasActive && !sequence.busy()){
        deferredSelection=-1;
        selectIdle(service);
        if(ready)enterReady();
      }else if(sequence.select(service,now,wasActive)) {
        log("서비스 전환: "+TITLES[sequence.working()]+" → "+TITLES[service]);
        ready=false;
        ptt.switchCall();
        inputGeneration++;
        requestScan();
      }
      return;
    }

    if(waking||sequence.busy()||now-lastControl<420)return;
    lastControl=now;
    if(!ready && !(callArmed && wasActive)){
      enterReady();
      return;
    }
    boolean start=ready;
    boolean accepted=start?sequence.startOrKeep(now):sequence.toggle(now,true);
    if(!accepted)return;
    ready=false;
    callArmed=start;
    ptt.normal();
    inputGeneration++;
    notice="";
    requestScan();
    save();
  }
  private void toggleContinuous(){
    if(!running)return;
    if(ptt.continuous())leaveContinuous();
    else enterContinuous();
  }
  private void leaveContinuous(){
    ptt.cancel();
    notice="음소거 대기로 돌아가는 중";
    lastControl=SystemClock.uptimeMillis();
    if(!unlocked()){wakeToMute=true;wakeForReady();}
    else pttChanged();
    updateFeedback();
  }
  private void enterContinuous(){
    ControllerLink link=midi;
    if(!running||!unlocked()||link==null||!link.connected()||ready||sequence.busy())return;
    if(ui.service!=sequence.selected()||ui.screen!=AppSequence.Screen.ACTIVE||ui.mic<0){
      notice="연속 대화를 시작하려면 먼저 통화를 연결하세요";
      save();
      return;
    }
    if(!ptt.beginContinuous())return;
    notice="연속 대화 · 연속 대화 버튼 또는 대화 버튼으로 음소거";
    requestScan();
    updateFeedback();
    save();
  }
  private void selectIdle(int service){
    sequence.selectIdle(service);
    getSharedPreferences("native-abc",0).edit().putInt("selected",service).apply();
    inputGeneration++;
  }
  private void enterReady(){
    if(deferredSelection>0 && deferredSelection!=sequence.selected()){
      int service=deferredSelection;
      boolean oldCall=calls.active(sequence.selected());
      if(sequence.busy()){prepareAfterSwitch=true;return;}
      if(oldCall){
        prepareAfterSwitch=true;
        sequence.selectForReady(service,SystemClock.uptimeMillis());
        requestScan();
        return;
      }
      selectIdle(service);
    }
    deferredSelection=-1;
    ready=open(sequence.selected(),"");
    if(ready)notice="준비됨 · 대화 버튼을 한 번 더 누르세요";
    updateFeedback();
    requestScan();
    save();
  }
  private volatile int assignments=VoiceService.DEFAULT_ASSIGNMENTS;
  private volatile int physicalSlot;
  boolean applyAssignments(int a,int b){
    if(sequence==null || sequence.busy() || actionPending || calls.anyActive()
        || ui.screen==AppSequence.Screen.ACTIVE || ui.screen==AppSequence.Screen.CONNECTING)return false;
    assignments=VoiceService.pack(a,b);
    getSharedPreferences(ControllerLink.SETTINGS,0).edit().putInt("assignments",assignments).apply();
    ptt.cancel();
    ready=false;
    callArmed=false;
    deferredSelection=-1;
    selectIdle(VoiceService.slot(assignments,physicalSlot));
    updateFeedback();
    requestScan();
    return true;
  }
  private int deferredSelection=-1;
  private boolean waking;
  private boolean wakeToMute;
  private boolean ready;
  private boolean callArmed;
  private boolean prepareAfterSwitch;
  private long lastControl;
  void configureBox(String ssid,String password){
    ControllerLink link=midi;
    if(link==null) throw new IllegalStateException("박스 연결 서비스를 먼저 켜주세요");
    link.configure(ssid,password);
  }
  boolean hasUsb(){ControllerLink link=midi;return link!=null && link.hasUsb();}
  void scanWifi(){if(midi!=null)midi.requestScan();}
  java.util.List<String> accessPoints(){return midi==null?java.util.Collections.emptyList():midi.accessPoints();}
  String wifiScanStatus(){return midi==null?"":midi.scanStatus();}
  String configurationStatus(){return midi==null?"":midi.configStatus();}
  private void wakeForReady(){
    if(waking)return;
    waking=true;
    try{
      startActivity(new Intent(this,WakeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      awaitScreen(SystemClock.elapsedRealtime()+4000);
    }catch(RuntimeException e){waking=false;wakeToMute=false;notice="화면 켜기 요청 실패";save();}
  }
  private void awaitScreen(long deadline){
    handler.postDelayed(()->{
      if(!running){waking=false;return;}
      if(unlocked()){
        waking=false;
        lastControl=SystemClock.uptimeMillis();
        if(wakeToMute){wakeToMute=false;pttChanged();}
        else enterReady();
      }else if(SystemClock.elapsedRealtime()<deadline)awaitScreen(deadline);
      else{waking=false;wakeToMute=false;notice="화면 잠금 해제 필요";save();}
    },220);
  }
  private boolean open(int service,String operation){
    try{
      Intent intent;
      if(operation.isEmpty())intent=getPackageManager().getLaunchIntentForPackage(VoiceService.PACKAGES[service]);
      else intent=new Intent(Intent.ACTION_VIEW,Uri.parse("https://www.sesame.com/mobile-app/"+operation)).setPackage(VoiceService.PACKAGES[VoiceService.SESAME]);
      if(intent==null)throw new IllegalStateException("공식 앱 미설치");
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);startActivity(intent);
      lastAction=TITLES[service]+": "+(operation.isEmpty()?"앱 열기":operation);log(lastAction);return true;
    }catch(Exception e){sequence.fail(TITLES[service]+" 실행 실패: "+e.getClass().getSimpleName());log(sequence.error);return false;}
  }
  private static String string(CharSequence value){return value==null?"":value.toString();}
  private AccessibilityNodeInfo appRoot(){
    AccessibilityNodeInfo root=getRootInActiveWindow();
    if(root!=null){if(serviceOf(root.getPackageName())!=0)return root;root.recycle();}
    // Compose can return a null active root. Never inspect an unfocused background app.
    AccessibilityNodeInfo best=null;int priority=0;
    for(AccessibilityWindowInfo window:getWindows()){
      try{
        int rank=window.isActive()?2:window.isFocused()?1:0;
        if(window.getType()!=AccessibilityWindowInfo.TYPE_APPLICATION||rank<=priority)continue;
        AccessibilityNodeInfo candidate=window.getRoot();if(candidate==null)continue;
        if(serviceOf(candidate.getPackageName())==0){candidate.recycle();continue;}
        if(best!=null)best.recycle();best=candidate;priority=rank;
      }finally{window.recycle();}
    }
    return best;
  }
  private Scan scan(){
    long scanDeadline=SystemClock.uptimeMillis()+350;
    Scan result=new Scan();AccessibilityNodeInfo root=appRoot();if(root==null)return result;
    result.ui.service=serviceOf(root.getPackageName());
    ArrayDeque<AccessibilityNodeInfo> queue=new ArrayDeque<>();queue.add(root);
    int seen=0,sesameControls=0;Set<String> controls=new LinkedHashSet<>();
    try{
      while(!queue.isEmpty()&&seen++<400&&SystemClock.uptimeMillis()<scanDeadline){
        AccessibilityNodeInfo node=queue.remove();
        try{
          String id=string(node.getViewIdResourceName()),cls=string(node.getClassName());
          // Do not traverse message lists, text-entry controls or scrollable history.
          if(node.isPassword()||cls.contains("EditText")||id.equals("chat.messageList")||id.equals("chat.input")||id.startsWith("chat.message:"))continue;
          
          if(result.ui.service!=VoiceService.SESAME && node.isScrollable() && (cls.contains("RecyclerView")||cls.contains("ScrollView")))continue;
          if(node.isVisibleToUser()&&node.isEnabled()){
            if(result.ui.service==VoiceService.SESAME){
              if(id.equals("home.callButton")||id.endsWith(":id/home.callButton")){sesameControls++;result.ui.button=string(node.getContentDescription());}
              if(id.equals("character_selector_name"))result.ui.agent=string(node.getText());
              if(id.equals("home.muteButton")||id.endsWith(":id/home.muteButton"))result.mic(node,string(node.getContentDescription()));
            }else{
              for(String label:new String[]{string(node.getContentDescription()),string(node.getText()),
                  Build.VERSION.SDK_INT>=28 && result.ui.service==VoiceService.GEMINI?string(node.getTooltipText()):""}){
                if(VoiceLabels.startFor(result.ui.service,label)){result.add(result.starts,node);controls.add(label);}
                if(VoiceLabels.endFor(result.ui.service,label)){result.add(result.ends,node);controls.add(label);}
                if(VoiceLabels.genericEnd(label)){result.add(result.genericEnds,node);controls.add(label);}
                if(VoiceLabels.mic(label)){result.add(result.mics,node);controls.add(label);}
                if(VoiceLabels.mic(label)||result.ui.service==VoiceService.GEMINI)result.mic(node,label);
                if(VoiceLabels.acknowledge(label))result.add(result.dismiss,node);
                if(VoiceLabels.limit(label)){result.limit=true;controls.add(label);}
                if(VoiceLabels.connecting(label))result.connecting=true;
              }
            }
          }
          for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo child=node.getChild(i);if(child!=null)queue.add(child);}
        }finally{node.recycle();}
      }
    }catch(RuntimeException e){result.close();throw e;}
    finally{result.ui.incomplete=!queue.isEmpty();while(!queue.isEmpty())queue.remove().recycle();}
    if(result.ui.service==VoiceService.SESAME){
      if(sesameControls>1)result.ui.screen=AppSequence.Screen.AMBIGUOUS;
      else if(sesameControls==1){
        if(VoiceLabels.sesameStart(result.ui.button))result.ui.screen=AppSequence.Screen.IDLE;
        if(VoiceLabels.sesameEnd(result.ui.button))result.ui.screen=AppSequence.Screen.ACTIVE;
      }
    }else{
      int starts=result.starts.size(),ends=result.ends.size(),genericEnds=result.genericEnds.size(),mics=result.mics.size();
      if(result.ui.service==VoiceService.GEMINI){
        result.classifyGeminiControls();
        int direct=VoiceLabels.micState(result.muteButtons.size(),result.unmuteButtons.size());
        if(direct>=0)result.ui.mic=direct;
        mics=result.ui.mic>=0?1:0;
      }
      result.ui.screen=result.ui.service==VoiceService.GEMINI
          ?VoiceLabels.classifyGemini(starts,ends,genericEnds,mics,result.connecting,result.limit,!result.dismiss.isEmpty())
          :VoiceLabels.classify(starts,ends,genericEnds,mics,result.connecting,result.limit,!result.dismiss.isEmpty());
      result.ui.controls=String.join(" / ",controls);result.ui.button=result.ui.screen.name();
    }
    if(result.ui.incomplete&&result.ui.screen!=AppSequence.Screen.BLOCKED){result.ui.screen=AppSequence.Screen.UNKNOWN;result.ui.mic=-1;}
    if(!result.ui.incomplete){
      if(result.muteButtons.size()==1&&result.unmuteButtons.isEmpty())result.ui.mic=0;
      if(result.unmuteButtons.size()==1&&result.muteButtons.isEmpty())result.ui.mic=1;
    }
    return result;
  }
  private boolean gestureClick(AccessibilityNodeInfo node,int service,boolean mute,long epoch)throws Exception{
    Rect bounds=new Rect();node.getBoundsInScreen(bounds);if(bounds.isEmpty())return false;
    Path path=new Path();path.moveTo(bounds.exactCenterX(),bounds.exactCenterY());
    GestureDescription gesture=new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(path,0,40)).build();
    CountDownLatch done=new CountDownLatch(1);boolean[] clicked={false};
    handler.post(()->{
      if(!running||!unlocked()||!ptt.permits(service,mute,epoch,SystemClock.uptimeMillis())){done.countDown();return;}
      if(!dispatchGesture(gesture,new GestureResultCallback(){
        @Override public void onCompleted(GestureDescription result){clicked[0]=true;done.countDown();}
        @Override public void onCancelled(GestureDescription result){done.countDown();}
      },handler))done.countDown();
    });
    return done.await(800,TimeUnit.MILLISECONDS)&&clicked[0];
  }
  private String microphone(int service,boolean mute,long epoch)throws Exception{
    try(Scan fresh=scan()){
      if(!ptt.permits(service,mute,epoch,SystemClock.uptimeMillis()))return "PTT 오래된 요청 취소";
      if(fresh.ui.service!=service)throw new IllegalStateException("PTT 앱 화면 미확인");
      if(fresh.ui.screen==AppSequence.Screen.IDLE||fresh.ui.screen==AppSequence.Screen.BLOCKED)return "PTT 통화 없음";
      if(fresh.ui.mic==(mute?1:0))return "PTT 마이크 상태 유지";
      List<AccessibilityNodeInfo> targets=fresh.micTargets(mute);
      if(targets.size()!=1)throw new IllegalStateException("PTT 고유 마이크 버튼 미확인");
      AccessibilityNodeInfo node=targets.get(0);
      if(!ptt.permits(service,mute,epoch,SystemClock.uptimeMillis()))return "PTT 오래된 요청 취소";
      if(!(node.isClickable()&&node.performAction(AccessibilityNodeInfo.ACTION_CLICK))&&!gestureClick(node,service,mute,epoch))throw new IllegalStateException("PTT 마이크 버튼 실행 실패");
      // Never click a toggle twice while Compose/React is still reporting its old state.
      long deadline=SystemClock.uptimeMillis()+1500;
      while(SystemClock.uptimeMillis()<deadline){
        if(!ptt.permits(service,mute,epoch,SystemClock.uptimeMillis()))return "PTT 요청 변경 · 재조회";
        SystemClock.sleep(40);
        Ui observed=readUi();
        if(observed.service!=service)return "PTT 화면 변경 · 후속 조회";
        if(observed.screen==AppSequence.Screen.IDLE||observed.screen==AppSequence.Screen.BLOCKED)return "PTT 통화 종료 확인";
        if(observed.mic==(mute?1:0))return TITLES[service]+(mute?": PTT 마이크 끄기 확인":": PTT 마이크 켜기 확인");
      }
      throw new IllegalStateException("PTT 마이크 상태 반영 미확인");
    }
  }
  private boolean reconcilePtt(){
    ptt.context(sequence.selected(),calls.active(sequence.selected()) || (ui.service==sequence.selected() && ui.screen==AppSequence.Screen.ACTIVE),sequence.busy());
    if(sequence.busy()&&!sequence.starting())return false; // C/old-call stop always outranks PTT.
    if(!ptt.enabled()||ui.service!=sequence.selected()||ui.mic<0||actionPending||uiLane.busy())return false;
    boolean mute=ptt.muted(SystemClock.uptimeMillis());
    if(ui.mic==(mute?1:0)||SystemClock.uptimeMillis()-lastMicAttempt<80)return false;
    int service=ui.service;long epoch=ptt.epoch();actionPending=true;lastMicAttempt=SystemClock.uptimeMillis();
    if(!uiLane.submit(()->microphone(service,mute,epoch),(result,error)->{
      actionPending=false;jobStartedAt=0;
      if(error==null){lastAction=result;log(result);}
      else if(!ptt.permits(service,mute,epoch,SystemClock.uptimeMillis()))log("PTT 이전 요청 폐기 · 최신 입력 적용");
      else if(!mute){
        notice="일반 모드 · 마이크 켜기 재확인 중";lastMicAttempt=SystemClock.uptimeMillis();
        Log.w("MayaNative","마이크 켜기 재확인 · 통화 유지",error);
      }
      else{
        notice="PTT 마이크 제어 실패 · 통화를 종료합니다";ptt.cancel();Log.w("MayaNative",notice,error);
        sequence.fail(notice);dispatch(service,AppSequence.Action.STOP,true);
      }
      requestScan();save();
    })){actionPending=false;return false;}
    return true;
  }
  private Ui readUi(){try(Scan current=scan()){return current.ui;}}
  private String voiceAction(int service,AppSequence.Action action)throws Exception{
    for(int attempt=0;attempt<2;attempt++){
      if(!running)throw new IllegalStateException("서비스 중지됨");
      try(Scan current=scan()){
        if(current.ui.service!=service)throw new IllegalStateException("선택 앱 화면이 아님");
        List<AccessibilityNodeInfo> targets;
        if(action==AppSequence.Action.START){
          if(current.ui.screen!=AppSequence.Screen.IDLE)throw new IllegalStateException("시작 화면 미확인");
          targets=current.starts;
        }else if(current.ui.screen==AppSequence.Screen.IDLE)return TITLES[service]+": 종료 상태 확인";
        else if(current.ui.screen==AppSequence.Screen.BLOCKED)targets=current.dismiss;
        else{
          if(current.ui.screen!=AppSequence.Screen.ACTIVE&&current.ui.screen!=AppSequence.Screen.CONNECTING)throw new IllegalStateException("종료 화면 미확인");
          targets=!current.ends.isEmpty()?current.ends:current.genericEnds;
        }
        if(targets.size()==1&&targets.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK))
          return TITLES[service]+": "+(action==AppSequence.Action.START?"음성 시작":current.ui.screen==AppSequence.Screen.BLOCKED?"한도 안내 닫기":"음성 종료");
      }
      SystemClock.sleep(80);
    }
    throw new IllegalStateException("고유 버튼 클릭 실패 · 입력 연결은 유지");
  }
  private void requestScan(){
    if(!running||sequence==null||uiLane==null||actionPending||uiLane.busy())return;
    long started=SystemClock.uptimeMillis(),generation=inputGeneration;
    if(uiLane.submit(this::readUi,(next,error)->{
      jobStartedAt=0;observerMillis=SystemClock.uptimeMillis()-started;
      if(!running)return;
      if(generation!=inputGeneration){requestScan();return;}
      if(error!=null){
        if(sequence.busy())sequence.fail("앱 화면 확인 오류 · USB 입력은 유지됩니다");
        notice="앱 화면 확인 재시도 중…";Log.w("MayaNative","화면 조회 오류",error);updateFeedback();save();return;
      }
      applyUi(next);
    }))jobStartedAt=started;
  }
  private void dispatch(int service,AppSequence.Action action,boolean cleanup){
    if(service==VoiceService.SESAME){
      open(1,action==AppSequence.Action.START?"call":"end-call");
      return;
    }
    if(action==AppSequence.Action.START)geminiRejected=false;
    actionPending=true;long started=SystemClock.uptimeMillis();
    if(!uiLane.submit(()->voiceAction(service,action),(result,error)->{
      actionPending=false;jobStartedAt=0;observerMillis=SystemClock.uptimeMillis()-started;
      if(!running)return;
      if(error==null){lastAction=result;log(result);}
      else{
        if(!cleanup)sequence.fail(TITLES[service]+"의 음성 버튼을 확인하지 못했어요");
        notice=TITLES[service]+" 화면 확인 실패";Log.w("MayaNative","음성 제어 오류",error);
      }
      updateFeedback();save();requestScan();
    })){
      actionPending=false;sequence.fail("화면 작업 대기 중 · 다시 선택하세요");
    }else jobStartedAt=started;
  }
  private void applyUi(Ui next){
    if(!running)return;
    ui=next;
    boolean confirmedActive=calls.observe(ui.service,ui.screen,SystemClock.uptimeMillis());
    if(sequence.stopping()&&ui.service==sequence.working()&&ui.screen==AppSequence.Screen.IDLE)calls.ended(ui.service);
    if(confirmedActive){
      if(ui.service!=VoiceService.SESAME)geminiRejected=false;
      if(!sequence.busy()&&ui.service==sequence.selected()){sequence.error="";notice="";}
    }
    if(VoiceService.valid(ui.service)&&ui.service!=VoiceService.SESAME&&ui.screen==AppSequence.Screen.BLOCKED)geminiRejected=true;
    if(VoiceService.valid(ui.service)&&ui.service!=VoiceService.SESAME&&ui.screen==AppSequence.Screen.IDLE)quotaDismissAttempted=false;
    if(sequence.busy()&&!unlocked())sequence.fail("화면이 잠겨 후속 명령을 중단했어요");
    if(reconcilePtt()){updateFeedback();save();return;}
    boolean wasBusy=sequence.busy();int before=sequence.completed,selectedBefore=sequence.selected();
    AppSequence.Action action=sequence.step(ui.service,ui.screen,SystemClock.uptimeMillis());
    if(sequence.selected()!=selectedBefore)getSharedPreferences("native-abc",0).edit().putInt("selected",sequence.selected()).apply();
    if(action==AppSequence.Action.OPEN)open(sequence.working(),"");
    else if(action!=AppSequence.Action.NONE){
      if(sequence.working()!=VoiceService.SESAME&&ui.screen==AppSequence.Screen.BLOCKED)quotaDismissAttempted=true;
      dispatch(sequence.working(),action,false);
    }else if(VoiceService.valid(ui.service)&&ui.service!=VoiceService.SESAME&&ui.screen==AppSequence.Screen.BLOCKED&&!sequence.busy()&&!quotaDismissAttempted){
      // Close the failed-call sheet once. Leave the selected LED and MIDI connection alive.
      quotaDismissAttempted=true;
      if(sequence.error.isEmpty())sequence.fail("선택 앱 음성 한도/오류");
      dispatch(ui.service,AppSequence.Action.STOP,true);
    }
    if(sequence.completed>before){
      if(ui.screen==AppSequence.Screen.IDLE){calls.ended(ui.service);callArmed=false;}
      notice="";log("상태 확인 완료: "+TITLES[sequence.selected()]+" / "+ui.screen);
      if(prepareAfterSwitch&&!sequence.busy()){prepareAfterSwitch=false;enterReady();}
    }
    else if(wasBusy&&!sequence.busy()&&!sequence.error.isEmpty()){prepareAfterSwitch=false;log(sequence.error);}
    updateFeedback();save();
  }
  private void updateFeedback(){
    if(sequence==null)return;
    cLed=sequence.busy()?(sequence.starting()?1:0):(calls.active(sequence.selected())?127:0);
    int mode=0;
    if(cLed==127 || (ui.service==VoiceService.GEMINI && ui.service==sequence.selected()
        && ui.screen==AppSequence.Screen.ACTIVE && ui.mic>=0)){
      mode=1;
      if(ui.service==sequence.selected()&&ui.mic>=0)mode=ui.mic==1?2:(ptt.continuous()?5:3);
    }
    if(!unlocked()){ready=false;callArmed=false;prepareAfterSwitch=false;mode=0;}
    else if(ready||(cLed==1 && mode!=2 && mode!=3 && mode!=5)) mode=4;
    if(mode!=feedback.mode && sequence.selected()==VoiceService.GEMINI)
      Log.i("MayaNative","Gemini Live LED mode="+mode+" mic="+ui.mic);
    feedback=new Feedback(sequence.selected(),cLed,mode);
  }
  private final Runnable tick=new Runnable(){@Override public void run(){
    if(!running)return;
    try{sequence.expire(SystemClock.uptimeMillis());updateFeedback();requestScan();save();}
    catch(RuntimeException e){if(sequence!=null)sequence.fail("제어 상태 복구 중 · USB 입력은 유지");Log.w("MayaNative","주기 복구",e);}
    finally{if(running)handler.postDelayed(this,ptt.enabled()?80:400);}
  }};
  private String status(){
    if(sequence==null)return "접근성 연결 중…";
    if(jobStartedAt>0&&SystemClock.uptimeMillis()-jobStartedAt>2000)return "앱 화면 응답 지연 · USB 연결과 입력은 유지";
    if(sequence.busy()||!sequence.error.isEmpty())return sequence.status();
    if(!notice.isEmpty())return notice;
    if(calls.active(sequence.selected()))return ptt.mode()?"누르고 말하기 · C로 종료":"음성 대화 중 · C로 종료";
    if(ui.service==sequence.selected()){
      if(ui.screen==AppSequence.Screen.BLOCKED)return "선택 앱 음성 한도/오류 안내 · 연결되지 않음";
      if(ui.screen==AppSequence.Screen.ACTIVE)return "대화 중 · C로 종료";
      if(ui.screen==AppSequence.Screen.IDLE)return "대기 · C로 대화 시작";
    }
    return "A "+TITLES[VoiceService.slot(assignments,0)]+" · B "+TITLES[VoiceService.slot(assignments,1)];
  }
  String diagnostics(){return "선택: "+TITLES[sequence.selected()]+"\n전환 목적: "+TITLES[sequence.target()]+"\nUSB: "+usb+"\n상태: "+status()+
    "\n관찰 앱: "+TITLES[ui.service]+"\n버튼: "+ui.button+"\n음성 컨트롤: "+ui.controls+"\n캐릭터: "+ui.agent+
    "\n화면 조회 시간: "+observerMillis+"ms\nUSB 주기: "+ioTicks+"\n입력 지연: "+inputLagMillis+"ms\n마지막 명령: "+lastAction+"\n입력 A/B/C: "+counts[0]+" / "+counts[1]+" / "+counts[2]+"\nC LED: "+cLed+"\n\n"+String.join("\n",history);}
  String summary(){return "선택: "+TITLES[sequence.selected()]+"\n"+usb+"\n\n"+status()+"\n입력 A/B/C: "+counts[0]+" / "+counts[1]+" / "+counts[2];}
  private void log(String message){if(history.size()>=40)history.removeFirst();history.addLast(message);Log.i("MayaNative",message);}
  private long lastStatusWrite;
  private void save(){
    long now=SystemClock.elapsedRealtime();
    if(now-lastStatusWrite<2000)return;
    lastStatusWrite=now;
    if(sequence==null)return;
    try{
      JSONObject value=new JSONObject().put("version",ReleaseVersion.CODE).put("usb",usb).put("connected",midi!=null&&midi.connected()).put("foreground",foregroundActive).put("usbWakeLock",usbWakeLock!=null&&usbWakeLock.isHeld())
        .put("selectedService",sequence.selected()).put("targetService",sequence.target()).put("observedService",ui.service)
        .put("status",status()).put("screen",ui.screen.name()).put("button",ui.button).put("controls",ui.controls).put("agent",ui.agent)
        .put("ioHeartbeat",ioTicks).put("observerMillis",observerMillis).put("inputLagMillis",inputLagMillis).put("scanIncomplete",ui.incomplete).put("geminiRejected",geminiRejected)
        .put("busy",sequence.busy()).put("error",sequence.error).put("completed",sequence.completed)
        .put("pttEnabled",ptt.enabled()).put("pttMode",ptt.mode()).put("pttHeld",ptt.held()).put("pttMuted",ptt.muted(SystemClock.uptimeMillis())).put("micState",ui.mic)
        .put("lastIntent",lastAction).put("cLed",cLed).put("modeLed",feedback.mode).put("a",counts[0]).put("b",counts[1]).put("c",counts[2]);
      String json=value.toString();if(json.equals(lastSaved))return;
      try(FileOutputStream out=openFileOutput("native-status.json",MODE_PRIVATE)){out.write(json.getBytes(StandardCharsets.UTF_8));}lastSaved=json;
    }catch(Exception e){Log.w("MayaNative","진단 상태 저장 실패",e);}
  }
}


















