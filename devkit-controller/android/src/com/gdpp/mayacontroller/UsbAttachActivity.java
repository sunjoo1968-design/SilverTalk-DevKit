package kr.silvertalk.devkit;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public final class UsbAttachActivity extends Activity {
  @Override public void onCreate(Bundle state){
    super.onCreate(state);
    SesameControlService service=SesameControlService.current;
    if(service!=null)service.requestForegroundSession();
    if(service==null)startActivity(new Intent(this,MainActivity.class));
    finish();
  }
}
