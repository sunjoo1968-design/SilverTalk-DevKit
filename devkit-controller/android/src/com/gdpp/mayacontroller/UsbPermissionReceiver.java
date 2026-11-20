package kr.silvertalk.devkit;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class UsbPermissionReceiver extends BroadcastReceiver {
  @Override public void onReceive(Context context, Intent intent) {
    // The transport polls UsbManager.hasPermission after Android resolves the dialog.
  }
}
