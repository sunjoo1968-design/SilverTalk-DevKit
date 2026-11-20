package kr.silvertalk.devkit;

import android.app.Activity;
import android.app.KeyguardManager;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.TextView;

public final class WakeActivity extends Activity {
  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    if (Build.VERSION.SDK_INT >= 27) {
      setShowWhenLocked(true);
      setTurnScreenOn(true);
    } else {
      getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                           WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
    }
    TextView label=new TextView(this);
    label.setTextSize(22);
    label.setText("음성 대화 준비 중\nSunjooAn · 1.10");
    setContentView(label);
    KeyguardManager lock=getSystemService(KeyguardManager.class);
    if (lock!=null && lock.isKeyguardLocked()) {
      lock.requestDismissKeyguard(this,new KeyguardManager.KeyguardDismissCallback() {
        @Override public void onDismissSucceeded(){ finish(); }
        @Override public void onDismissError(){ finish(); }
        @Override public void onDismissCancelled(){ finish(); }
      });
    } else {
      label.postDelayed(this::finish,350);
    }
    label.postDelayed(this::finish,2200);
  }
}








