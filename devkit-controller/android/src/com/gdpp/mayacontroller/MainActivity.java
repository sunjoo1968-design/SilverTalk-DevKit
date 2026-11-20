package kr.silvertalk.devkit;

import android.app.*;
import android.content.*;
import android.hardware.usb.*;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.WindowInsets;
import android.widget.*;
import java.util.Locale;

public final class MainActivity extends Activity {
  private final Handler handler=new Handler(Looper.getMainLooper());
  private TextView status;
  private EditText address;
  private TextView wifiScan;
  private LinearLayout wifiNetworks;
  private java.util.List<String> displayedNetworks=java.util.Collections.emptyList();
  private LinearLayout setup;
  private boolean setupOpen;
  private android.content.SharedPreferences prefs(){return getSharedPreferences(ControllerLink.SETTINGS,0);}
  private String digest(String value){
    try{
      byte[] bytes=java.security.MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP);
    }catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
  }
  private void askPin(boolean setting){
    EditText pin=new EditText(this);
    pin.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);
    new AlertDialog.Builder(this).setTitle(setting?"보호자 PIN 설정":"보호자 PIN").setView(pin)
      .setNegativeButton("취소",null).setPositiveButton("확인",(dialog,button)->{
        String value=pin.getText().toString();
        if(setting){
          if(!value.matches("[0-9]{4,8}")){message("숫자 4~8자리를 입력하세요.");return;}
          String salt=java.util.UUID.randomUUID().toString();
          prefs().edit().putString("pinSalt",salt).putString("pinHash",digest(salt+value)).apply();
          message("PIN을 저장했습니다.");return;
        }
        if(!digest(prefs().getString("pinSalt","")+value).equals(prefs().getString("pinHash",""))){
          message("보호자 PIN이 다릅니다.");return;
        }
        showSetup(true);
      }).show();
  }
  private void showSetup(boolean visible){
    setupOpen=visible;
    setup.setVisibility(visible?android.view.View.VISIBLE:android.view.View.GONE);
  }
  @Override public void onCreate(Bundle saved) {
    super.onCreate(saved);
    LinearLayout page=new LinearLayout(this);
    page.setOrientation(LinearLayout.VERTICAL);
    page.setPadding(24,24,24,24);
    text(page,"SilverTalk 박스 설정",28);
    text(page,"SunjooAn · 1.10",15);
    status=text(page,"",18);
    LinearLayout home=page;
    button(home,"보호자 설정",()->{
      if(setupOpen){showSetup(false);return;}
      if(prefs().contains("pinHash"))askPin(false);
      else showSetup(true);
    });
    setup=new LinearLayout(this);
    setup.setOrientation(LinearLayout.VERTICAL);
    home.addView(setup);
    page=setup;
    Switch always=new Switch(this);
    always.setText("자동 대기 · 화면이 꺼져도 연결 유지");
    always.setChecked(prefs().getBoolean("always",true));
    page.addView(always);
    always.setOnCheckedChangeListener((view,on)->{
      prefs().edit().putBoolean("always",on).apply();
      SesameControlService service=SesameControlService.current;
      if(service!=null)service.applyAlwaysOn();
    });
    if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
      button(page,"재부팅 안내 알림 허용",()->requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},620));
    button(page,"보호자 PIN 설정",()->askPin(true));
    text(page,"먼저 갤럭시에서 두 공식 앱에 로그인하고 음성 대화가 되는지 확인하세요.",16);
    addServiceAssignments(page);
    text(page,"접근성 설정에서 ‘SilverTalk 박스 연결’을 켜고 이 화면으로 돌아오세요.",16);
    button(page,"접근성 설정 열기",()->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
    text(page,"PD 허브에 충전기와 박스를 꽂고 USB 연결을 허용하세요.",16);
    button(page,"박스 USB 연결 허용",this::allowUsb);

    button(page,"절전 설정 열기",()->startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)));
    text(page,"Wi-Fi 예비 연결",22);
    text(page,"갤럭시가 연결된 집 Wi-Fi를 박스로 보냅니다. 비밀번호를 입력할 때 USB 케이블을 연결해 두세요.",16);
    button(page,"갤럭시의 현재 Wi-Fi 사용",()->useCurrentWifi(true));
    wifiScan=text(page,"",15);
    button(page,"다른 Wi-Fi 검색",this::scanNetworks);
    wifiNetworks=new LinearLayout(this);
    wifiNetworks.setOrientation(LinearLayout.VERTICAL);
    page.addView(wifiNetworks);
    text(wifiNetworks,"갤럭시와 다른 네트워크를 쓸 때 검색하세요.",15);
    text(page,"갤럭시를 같은 공유기에 연결하세요. 등록한 박스의 IP는 자동으로 찾습니다. 아래는 수동 설정입니다.",16);
    address=field(page,"OLED의 IP 주소",InputType.TYPE_CLASS_PHONE);
    address.setText(getSharedPreferences(ControllerLink.SETTINGS,0).getString("ip",""));
    button(page,"박스 IP 주소 저장",this::saveIp);
    button(page,"연결 진단",()->{
      SesameControlService service=SesameControlService.current;
      Intent battery=registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
      boolean plugged=battery!=null && battery.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)!=0;
      message((plugged?"갤럭시 외부 전원 연결됨":"갤럭시 외부 전원 미감지")+"\n"+
        (service==null?"박스 연결 서비스 꺼짐":service.summary())+
        "\nWi-Fi 점검 시 박스에 별도 USB 전원을 연결해 동작을 확인하세요.");
    });
    text(page,"일정 읽기",22);
    Switch calendar=new Switch(this);
    calendar.setText("캘린더 일정 알림을 한국어로 읽기");
    calendar.setChecked(prefs().getBoolean("calendarVoice",false));
    page.addView(calendar);
    calendar.setOnCheckedChangeListener((view,on)->{
      prefs().edit().putBoolean("calendarVoice",on).apply();
      if(on && checkSelfPermission(android.Manifest.permission.READ_CALENDAR)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
        requestPermissions(new String[]{android.Manifest.permission.READ_CALENDAR},610);
      else CalendarVoice.refresh(this);
    });
    button(page,"일정 읽기 소리 시험",()->CalendarVoice.say(this,"일정 알림. 실버토크 시험입니다.",()->{}));
    if(Build.VERSION.SDK_INT>=31) button(page,"정확한 알림 설정",()->
      startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
          android.net.Uri.parse("package:"+getPackageName()))));
    showSetup(!prefs().contains("pinHash"));
    ScrollView scroll=new ScrollView(this);
    scroll.addView(home);
    setContentView(scroll);
    scroll.setOnApplyWindowInsetsListener((view,insets)->{
      view.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),
          insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
      return insets;
    });
    scroll.requestApplyInsets();
  }

  private void useCurrentWifi(boolean askPermission){
    SesameControlService service=SesameControlService.current;
    if(service==null || !service.hasUsb()){message("박스를 USB로 연결하고 허용한 뒤 다시 눌러주세요.");return;}
    String fine=android.Manifest.permission.ACCESS_FINE_LOCATION;
    if(checkSelfPermission(fine)!=android.content.pm.PackageManager.PERMISSION_GRANTED){
      if(askPermission){
        new AlertDialog.Builder(this).setMessage("Android에서 현재 Wi-Fi 이름을 읽으려면 정확한 위치 권한이 필요합니다. 위치 정보는 저장하지 않습니다.")
          .setPositiveButton("계속",(dialog,button)->requestPermissions(new String[]{android.Manifest.permission.ACCESS_COARSE_LOCATION,fine},630))
          .setNegativeButton("취소",null).show();
      }else message("현재 Wi-Fi 이름을 보려면 정확한 위치 권한을 허용하거나 다른 Wi-Fi 검색을 사용하세요.");
      return;
    }
    android.net.wifi.WifiManager manager=(android.net.wifi.WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
    android.net.wifi.WifiInfo link=null;
    try{if(manager!=null)link=manager.getConnectionInfo();}catch(SecurityException ignored){}
    String name=link==null?null:link.getSSID();
    if(name==null || name.isEmpty() || name.equals(android.net.wifi.WifiManager.UNKNOWN_SSID) ||
        link.getSupplicantState()!=android.net.wifi.SupplicantState.COMPLETED){
      message("갤럭시가 집 Wi-Fi에 연결됐는지와 위치 기능이 켜져 있는지 확인하세요. 다른 Wi-Fi 검색도 사용할 수 있습니다.");
      return;
    }
    if(name.length()>=2 && name.charAt(0)==34 && name.charAt(name.length()-1)==34)
      name=name.substring(1,name.length()-1);
    else{
      message("이 Wi-Fi 이름을 읽을 수 없습니다. 다른 Wi-Fi 검색을 사용하세요.");
      return;
    }
    connectNetwork(name);
  }

  private void scanNetworks(){
    SesameControlService service=SesameControlService.current;
    if(service==null){message("접근성에서 박스 연결을 먼저 켜주세요.");return;}
    if(!service.hasUsb()){message("Wi-Fi를 설정하려면 먼저 USB로 박스를 연결하고 허용하세요.");return;}
    service.scanWifi();
    wifiScan.setText("검색 중…");
  }

  private void showNetworks(java.util.List<String> networks){
    if(networks.equals(displayedNetworks))return;
    displayedNetworks=new java.util.ArrayList<>(networks);
    wifiNetworks.removeAllViews();
    for(String name:displayedNetworks)button(wifiNetworks,name,()->connectNetwork(name));
  }

  private void connectNetwork(String name){
    EditText password=new EditText(this);
    password.setHint("Wi-Fi 비밀번호");
    password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
    AlertDialog dialog=new AlertDialog.Builder(this).setTitle(name)
        .setView(password).setNegativeButton("취소",null).setPositiveButton("연결",null).create();
    dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view->{
      SesameControlService service=SesameControlService.current;
      if(service==null||!service.hasUsb()){password.setError("박스를 USB로 연결해 주세요.");return;}
      try{
        service.configureBox(name,password.getText().toString());
        password.setText("");
        dialog.dismiss();
      }catch(IllegalArgumentException|IllegalStateException error){password.setError(error.getMessage());}
    }));
    dialog.show();
  }

  private void allowUsb(){
    UsbManager manager=getSystemService(UsbManager.class);
    UsbDevice board=ControllerLink.findBoard(manager);
    if(board==null){message("CH340 DevKit 한 대를 데이터 케이블로 연결하고 다시 눌러주세요.");return;}
    if(manager.hasPermission(board)){message("USB 사용이 허용되어 있습니다.");return;}
    PendingIntent result=PendingIntent.getBroadcast(this,0,
        new Intent(this,UsbPermissionReceiver.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_MUTABLE);
    manager.requestPermission(board,result);
  }

  private void saveIp(){
    String host=address.getText().toString().trim();
    String[] parts=host.split("\\.",-1);
    if(!host.isEmpty()){
      if(parts.length!=4){message("OLED에 보이는 숫자 네 묶음을 입력하세요.");return;}
      for(String part:parts){
        if(!part.matches("[0-9]{1,3}")||Integer.parseInt(part)>255){message("IP 주소를 다시 확인하세요.");return;}
      }
    }
    getSharedPreferences(ControllerLink.SETTINGS,0).edit().putString("ip",host).apply();
    message(host.isEmpty()?"Wi-Fi 주소를 지웠습니다.":"저장했습니다. 박스에 별도 전원을 연결해 Wi-Fi 통신을 확인하세요.");
  }

  private void addServiceAssignments(LinearLayout page){
    text(page,"A·B 서비스 배정",22);
    text(page,"박스 선택 스위치의 각 위치에 사용할 음성 앱을 고르세요.",16);
    int pair=prefs().getInt("assignments",VoiceService.DEFAULT_ASSIGNMENTS);
    String[] names=java.util.Arrays.copyOfRange(VoiceService.TITLES,1,VoiceService.COUNT+1);
    Spinner a=serviceSpinner(page,"A 위치",names,VoiceService.slot(pair,0));
    Spinner b=serviceSpinner(page,"B 위치",names,VoiceService.slot(pair,1));
    button(page,"A·B 배정 저장",()->{
      int left=a.getSelectedItemPosition()+1,right=b.getSelectedItemPosition()+1;
      SesameControlService service=SesameControlService.current;
      if(service!=null && !service.applyAssignments(left,right)){
        message("음성 대화를 종료한 뒤 다시 저장하세요.");
        return;
      }
      prefs().edit().putInt("assignments",VoiceService.pack(left,right)).apply();
      message("배정을 저장했습니다. 박스가 연결되면 OLED에 표시됩니다.");
    });
    button(page,"A 앱 열기",()->open(VoiceService.PACKAGES[a.getSelectedItemPosition()+1]));
    button(page,"B 앱 열기",()->open(VoiceService.PACKAGES[b.getSelectedItemPosition()+1]));
  }

  private Spinner serviceSpinner(LinearLayout page,String label,String[] names,int selected){
    TextView description=text(page,label,16);
    Spinner spinner=new Spinner(this);
    spinner.setId(android.view.View.generateViewId());
    description.setLabelFor(spinner.getId());
    spinner.setContentDescription(label);
    ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,names);
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    spinner.setAdapter(adapter);
    spinner.setSelection(selected-1);
    page.addView(spinner);
    return spinner;
  }

  private void open(String packageName){
    Intent launch=getPackageManager().getLaunchIntentForPackage(packageName);
    if(launch==null){message("갤럭시에 공식 앱을 먼저 설치하세요.");return;}
    startActivity(launch);
  }

  private TextView text(LinearLayout page,String content,float size){
    TextView view=new TextView(this);
    view.setText(content);
    view.setTextSize(size);
    view.setPadding(0,12,0,8);
    page.addView(view);
    return view;
  }

  private EditText field(LinearLayout page,String hint,int type){
    EditText field=new EditText(this);
    field.setHint(hint);
    field.setInputType(type);
    field.setSingleLine();
    page.addView(field);
    return field;
  }

  private void button(LinearLayout page,String label,Runnable action){
    Button button=new Button(this);
    button.setText(label);
    button.setMinHeight((int)(48*getResources().getDisplayMetrics().density));
    button.setOnClickListener(view->action.run());
    page.addView(button);
  }

  private void message(String text){
    new AlertDialog.Builder(this).setMessage(text).setPositiveButton("확인",null).show();
  }

  private final Runnable tick=new Runnable(){
    @Override public void run(){
      SesameControlService service=SesameControlService.current;
      if(service==null)status.setText("박스 연결 서비스 꺼짐");
      else{
        service.requestForegroundSession();
        status.setText(service.summary()+"\n"+service.configurationStatus());
        if(setupOpen){wifiScan.setText(service.wifiScanStatus());showNetworks(service.accessPoints());}
      }
      handler.postDelayed(this,450);
    }
  };
  @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
    super.onRequestPermissionsResult(request,permissions,results);
    if(request==630){useCurrentWifi(false);return;}
    if(request==610){
      CalendarVoice.refresh(this);
      if(!CalendarVoice.enabled(this))message("일정을 읽으려면 캘린더 권한이 필요합니다.");
    }
  }
  @Override protected void onResume(){super.onResume();CalendarVoice.refresh(this);handler.post(tick);}
  @Override protected void onPause(){handler.removeCallbacks(tick);super.onPause();}
}











