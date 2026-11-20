package kr.silvertalk.devkit;

import android.Manifest;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.*;
import android.provider.CalendarContract;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

public final class CalendarVoice extends BroadcastReceiver {
  private static final ExecutorService WORKER=Executors.newSingleThreadExecutor();
  private static final String RING="kr.silvertalk.devkit.CALENDAR_VOICE";
  private static final long MINUTE=60000, DAY=86400000;

  static void refresh(Context context){
    Context app=context.getApplicationContext();
    WORKER.execute(()->schedule(app));
  }

  static boolean enabled(Context context){
    return context.getSharedPreferences(ControllerLink.SETTINGS,0).getBoolean("calendarVoice",false)
        && context.checkSelfPermission(Manifest.permission.READ_CALENDAR)==PackageManager.PERMISSION_GRANTED;
  }

  @Override public void onReceive(Context context,Intent intent){
    Context app=context.getApplicationContext();
    if(Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) notifyController(app);
    PendingResult result=goAsync();
    long scheduled=intent.getLongExtra("at",0);
    String title=intent.getStringExtra("title");
    long late=System.currentTimeMillis()-scheduled;
    boolean speak=RING.equals(intent.getAction()) && enabled(app) && title!=null && late>=-1000 && late<5*MINUTE;
    WORKER.execute(()->{
      try{schedule(app);}
      finally{
        if(speak)new Handler(Looper.getMainLooper()).post(()->say(app,"일정 알림. "+title,result::finish));
        else result.finish();
      }
    });
  }
  private static void notifyController(Context context){
    if(!context.getSharedPreferences(ControllerLink.SETTINGS,0).getBoolean("always",true)
        || SesameControlService.current!=null)return;
    android.app.NotificationManager manager=context.getSystemService(android.app.NotificationManager.class);
    String channel="controller-boot";
    manager.createNotificationChannel(new android.app.NotificationChannel(channel,"박스 연결 확인",
        android.app.NotificationManager.IMPORTANCE_DEFAULT));
    PendingIntent open=PendingIntent.getActivity(context,620,new Intent(context,MainActivity.class),PendingIntent.FLAG_IMMUTABLE);
    if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return;
    manager.notify(620,new android.app.Notification.Builder(context,channel)
        .setSmallIcon(android.R.drawable.ic_menu_manage).setContentTitle("SilverTalk 박스 연결 확인")
        .setContentText("박스가 연결되지 않으면 이 알림을 눌러 확인하세요.").setContentIntent(open).setAutoCancel(true).build());
  }

  private static void schedule(Context context){
    AlarmManager alarms=context.getSystemService(AlarmManager.class);
    Intent intent=new Intent(context,CalendarVoice.class).setAction(RING);
    PendingIntent old=PendingIntent.getBroadcast(context,601,intent,PendingIntent.FLAG_NO_CREATE|PendingIntent.FLAG_IMMUTABLE);
    if(!enabled(context)){if(old!=null)alarms.cancel(old);return;}
    long now=System.currentTimeMillis(), due=Long.MAX_VALUE;
    String title=null;
    Uri.Builder list=CalendarContract.Instances.CONTENT_URI.buildUpon();
    ContentUris.appendId(list,now);
    ContentUris.appendId(list,now+14*DAY);
    ContentResolver resolver=context.getContentResolver();
    String[] cols={"event_id","begin","title"};
    try(Cursor events=resolver.query(list.build(),cols,"visible=1 AND allDay=0 AND (eventStatus IS NULL OR eventStatus!=2)",null,"begin ASC")){
      while(events!=null && events.moveToNext()){
        try(Cursor reminders=resolver.query(CalendarContract.Reminders.CONTENT_URI,new String[]{"minutes"},
            "event_id=? AND (method=0 OR method=1)",new String[]{events.getString(0)},null)){
          while(reminders!=null && reminders.moveToNext()){
            int minutes=reminders.getInt(0);
            long at=events.getLong(1)-minutes*MINUTE;
            if(minutes>=0 && at>now && at<due){due=at;title=events.getString(2);}
          }
        }
      }
    }catch(RuntimeException ignored){return;}
    if(title==null){if(old!=null)alarms.cancel(old);return;}
    intent.putExtra("title",title.substring(0,Math.min(140,title.length()))).putExtra("at",due);
    PendingIntent next=PendingIntent.getBroadcast(context,601,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    try{
      if(Build.VERSION.SDK_INT<31 || alarms.canScheduleExactAlarms())
        alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,due,next);
      else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,due,next);
    }catch(SecurityException e){alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,due,next);}
  }

  static void say(Context context,String text,Runnable complete){
    Handler handler=new Handler(Looper.getMainLooper());
    TextToSpeech[] engine=new TextToSpeech[1];
    boolean[] closed={false};
    Runnable stop=()->{
      if(closed[0])return;
      closed[0]=true;
      if(engine[0]!=null){engine[0].stop();engine[0].shutdown();}
      complete.run();
    };
    engine[0]=new TextToSpeech(context,status->handler.post(()->{
      if(closed[0])return;
      TextToSpeech tts=engine[0];
      if(status!=TextToSpeech.SUCCESS || tts.setLanguage(Locale.KOREA)<0){stop.run();return;}
      tts.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
          .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
      tts.setOnUtteranceProgressListener(new UtteranceProgressListener(){
        @Override public void onStart(String id){}
        @Override public void onDone(String id){handler.post(stop);}
        @Override public void onError(String id){handler.post(stop);}
      });
      if(tts.speak(text,TextToSpeech.QUEUE_FLUSH,null,"calendar-reminder")==TextToSpeech.ERROR)stop.run();
    }));
    handler.postDelayed(stop,8500);
  }
}



