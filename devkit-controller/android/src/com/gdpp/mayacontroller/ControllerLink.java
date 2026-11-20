package kr.silvertalk.devkit;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.usb.*;
import android.os.SystemClock;
import com.hoho.android.usbserial.driver.Ch34xSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class ControllerLink {
  interface Listener {
    void press(int key);
    void longPress();
    void continuousPress();
    void status(String message);
    void ptt(boolean held);
    void pttReady();
    void disconnected();
  }
  static final String SETTINGS="controller-link";
  static final int PORT=41726;
  private static final long USB_LINK_TIMEOUT_MS=550, WIFI_LINK_TIMEOUT_MS=1500;
  private final Listener listener;
  private final UsbManager usb;
  private final SharedPreferences settings;
  private final SecureRandom random=new SecureRandom();
  private UsbSerialPort port;
  private UsbDevice attached;
  private volatile boolean connected;
  private volatile String transport="";
  private long boot=-1, clicks=-1, longPresses=-1, continuousPresses=-1;
  private int choice=0;
  private boolean released=false;
  private long lastAnswer=0, nextUsbOpen=0;
  private volatile String configuration;
  private volatile boolean scanRequested;
  private volatile String scanStatus="";
  private volatile java.util.List<String> accessPoints=java.util.Collections.emptyList();
  private final java.util.Set<String> scanResults=new java.util.LinkedHashSet<>();
  private long scanDeadline, nextDiscovery;
  private volatile String configStatus="";
  private final ByteArrayOutputStream serialLine=new ByteArrayOutputStream();

  ControllerLink(Context context, Listener listener) {
    usb=context.getSystemService(UsbManager.class);
    settings=context.getSharedPreferences(SETTINGS,0);
    this.listener=listener;
  }

  static UsbDevice findBoard(UsbManager usb) {
    UsbDevice match=null;
    if (usb==null) return null;
    for (UsbDevice device:usb.getDeviceList().values()) {
      if (device.getVendorId()!=0x1a86 || device.getProductId()!=0x7523) continue;
      if (match!=null) return null;
      match=device;
    }
    return match;
  }

  boolean connected(){return connected;}
  boolean hasUsb(){return connected && transport.equals("USB");}
  String configStatus(){return configStatus;}

  void requestScan() {
    accessPoints=java.util.Collections.emptyList();
    scanStatus="검색 요청 대기";
    scanRequested=true;
  }
  java.util.List<String> accessPoints(){return accessPoints;}
  String scanStatus(){return scanStatus;}

  void configure(String ssid,String pass) {
    byte[] name=ssid.getBytes(StandardCharsets.UTF_8), password=pass.getBytes(StandardCharsets.UTF_8);
    if (name.length==0 || name.length>32 || (password.length!=0 && (password.length<8 || password.length>63)))
      throw new IllegalArgumentException("Wi-Fi 이름은 1~32바이트, 비밀번호는 8~63바이트여야 합니다.");
    String key=settings.getString("key","");
    if (key.isEmpty()) {
      key=nonce(16);
      settings.edit().putString("key",key).apply();
    }
    configuration="CFG "+hex(name)+" "+(password.length==0?"-":hex(password))+" "+key;
    configStatus="USB로 Wi-Fi 설정 전송 대기";
  }



  void feedback(int selectedSlot,int led,int mode,int assignments) {
    String nonce=nonce(12);
    String request="P "+nonce+" "+selectedSlot+" "+mode+" 4 "+VoiceService.slot(assignments,0)+" "+VoiceService.slot(assignments,1);
    try {
      openUsb();
      if (scanDeadline!=0 && SystemClock.elapsedRealtime()>scanDeadline) {
        scanDeadline=0;
        scanStatus="검색 시간 초과 · 다시 검색하세요";
      }
      if (port!=null && scanRequested && scanDeadline==0) {
        scanRequested=false;
        scanResults.clear();
        scanStatus="박스에서 Wi-Fi 검색 중";
        scanDeadline=SystemClock.elapsedRealtime()+12000;
        String reply=usbExchange("SCAN","SCAN START","SCAN BUSY",250);
        if ("SCAN BUSY".equals(reply)) {
          scanDeadline=0;
          scanStatus="박스 Wi-Fi 연결 중 · 잠시 후 다시 검색하세요";
        }
      }
      String cfg=configuration;
      if (port!=null && cfg!=null && connected && transport.equals("USB")) {
        String reply=usbExchange(cfg,"OK REBOOT","ERR CONFIG",500);
        if (reply!=null) {
          configuration=null;
          configStatus=reply.startsWith("OK")?"전송 완료 · 잠시 후 OLED IP를 입력하세요":"보드에서 Wi-Fi 설정을 거절했습니다";
          closeUsb();
          if (configStatus.startsWith("전송")) nextUsbOpen=SystemClock.elapsedRealtime()+1800;
          disconnect();
          return;
        }
      }
      if (port!=null) {
        String response=usbExchange(request,"S "+nonce+" ",null,135);
        if (response!=null && accept(response,nonce,"USB")) return;
      }
    } catch (IOException | RuntimeException failure) { closeUsb(); }

    String ip=settings.getString("ip",""), key=settings.getString("key","");
    if (!ip.isEmpty() && !key.isEmpty()) {
      try {
        String response=wifiExchange(ip,request,key);
        int split=response.lastIndexOf(' ');
        if (split>0) {
          String payload=response.substring(0,split);
          String signature=response.substring(split+1);
          if (MessageDigest.isEqual(signature.getBytes(StandardCharsets.US_ASCII),
                     signature(payload,key).getBytes(StandardCharsets.US_ASCII)) &&
              accept(payload,nonce,"Wi-Fi")) return;
        }
      } catch (Exception ignored) { }
    }
    long now=SystemClock.elapsedRealtime();
    // Allow Wi-Fi retransmissions to recover before dropping live microphone mode.
    long linkTimeout=transport.equals("Wi-Fi")?WIFI_LINK_TIMEOUT_MS:USB_LINK_TIMEOUT_MS;
    if (connected && now-lastAnswer>=linkTimeout) disconnect();
    if (!connected && !key.isEmpty() && now>=nextDiscovery) {
      nextDiscovery=now+3200;
      try {
        String found=discover(key);
        if (!found.isEmpty()) {
          settings.edit().putString("ip",found).apply();
        }
      } catch (Exception ignored) { }
    }
  }

  private void openUsb() throws IOException {
    UsbDevice device=findBoard(usb);
    if (port!=null && (!attached.equals(device) || !usb.hasPermission(attached))) closeUsb();
    if (port!=null || device==null || !usb.hasPermission(device) ||
        SystemClock.elapsedRealtime()<nextUsbOpen) return;
    nextUsbOpen=SystemClock.elapsedRealtime()+1500;
    UsbDeviceConnection connection=usb.openDevice(device);
    if (connection==null) return;
    UsbSerialPort candidate=new Ch34xSerialDriver(device).getPorts().get(0);
    try {
      candidate.open(connection);
      candidate.setParameters(115200,8,UsbSerialPort.STOPBITS_1,UsbSerialPort.PARITY_NONE);
      serialLine.reset();
      port=candidate;
      attached=device;
    } catch (IOException | RuntimeException e) {
      try { candidate.close(); } catch (Exception ignored) { }
      connection.close();
      throw e;
    }
  }

  private String usbExchange(String request,String prefix,String other,int timeout) throws IOException {
    port.write((request+"\n").getBytes(StandardCharsets.US_ASCII),75);
    long deadline=SystemClock.elapsedRealtime()+timeout;
    byte[] data=new byte[256];
    while (SystemClock.elapsedRealtime()<deadline) {
      String matching=null;
      int length=port.read(data,25);
      for (int i=0;i<length;i++) {
        if (data[i]=='\n') {
          String line=new String(serialLine.toByteArray(),StandardCharsets.US_ASCII).trim();
          serialLine.reset();
          readScan(line);
          if (line.startsWith(prefix) || (other!=null && line.equals(other))) matching=line;
        } else if (serialLine.size()<255) serialLine.write(data[i]);
      }
      if (matching!=null) return matching;
    }
    return null;
  }

  private void readScan(String line) {
    if(scanDeadline==0)return;
    if(line.equals("SCAN END")){
      accessPoints=new java.util.ArrayList<>(scanResults);
      scanDeadline=0;
      scanStatus=scanResults.isEmpty()?"검색된 네트워크 없음":"검색 완료 · 목록에서 선택하세요";
      return;
    }
    if(!line.matches("AP (?:[0-9a-f]{2}){1,32}"))return;
    byte[] bytes=new byte[(line.length()-3)/2];
    for(int i=0;i<bytes.length;i++) bytes[i]=(byte)Integer.parseInt(line.substring(3+i*2,5+i*2),16);
    scanResults.add(new String(bytes,StandardCharsets.UTF_8));
  }

  private String discover(String key) throws Exception {
    String request="D "+nonce(12);
    String expected="HERE "+request.substring(2);
    String signed=expected+" "+signature(expected,key);
    byte[] message=(request+" "+signature(request,key)).getBytes(StandardCharsets.US_ASCII);
    try(DatagramSocket socket=new DatagramSocket()){
      socket.setBroadcast(true);
      socket.setSoTimeout(140);
      socket.send(new DatagramPacket(message,message.length,InetAddress.getByName("255.255.255.255"),PORT));
      byte[] buffer=new byte[128];
      DatagramPacket packet=new DatagramPacket(buffer,buffer.length);
      long deadline=SystemClock.elapsedRealtime()+160;
      while(SystemClock.elapsedRealtime()<deadline){
        socket.receive(packet);
        if (packet.getPort()==PORT && MessageDigest.isEqual(
            signed.getBytes(StandardCharsets.US_ASCII),
            java.util.Arrays.copyOf(buffer,packet.getLength()))) return packet.getAddress().getHostAddress();
        packet.setLength(buffer.length);
      }
    }
    return "";
  }

  private String wifiExchange(String ip,String request,String key) throws Exception {
    try (Socket socket=new Socket()) {
      socket.connect(new InetSocketAddress(ip,PORT),110);
      socket.setSoTimeout(140);
      socket.getOutputStream().write((request+" "+signature(request,key)+"\n").getBytes(StandardCharsets.US_ASCII));
      InputStream input=socket.getInputStream();
      ByteArrayOutputStream response=new ByteArrayOutputStream();
      for (int i=0;i<255;i++) {
        int value=input.read();
        if (value=='\n') return new String(response.toByteArray(),StandardCharsets.US_ASCII);
        if (value<0) break;
        response.write(value);
      }
      return "";
    }
  }

  private boolean accept(String response,String nonce,String path) {
    String[] fields=response.split(" ");
    if ((fields.length<6 || fields.length>8) || !fields[0].equals("S") || !fields[1].equals(nonce)) return false;
    try {
      long id=Long.parseLong(fields[2]), count=Long.parseLong(fields[3]);
      int nextChoice=Integer.parseInt(fields[4]), held=Integer.parseInt(fields[5]);
      long longCount=fields.length>=7?Long.parseLong(fields[6]):-1;
      long continuousCount=fields.length==8?Long.parseLong(fields[7]):-1;
      if (id<0 || count<0 || nextChoice<1 || nextChoice>2 || held<0 || held>1) return false;
      boolean fresh=boot!=id || !connected || !transport.equals(path);
      if (fresh) { listener.disconnected(); released=false; }
      boot=id;
      connected=true;
      transport=path;
      lastAnswer=SystemClock.elapsedRealtime();
      listener.status(path+" 연결됨");
      listener.pttReady();
      if (choice!=nextChoice) listener.press(nextChoice-1);
      choice=nextChoice;
      if (!fresh && count==((clicks+1)&0xffffffffL)) listener.press(2);
      if (!fresh && longPresses>=0 && longCount==((longPresses+1)&0xffffffffL)) listener.longPress();
      clicks=count;
      longPresses=longCount;
      if (!fresh && continuousPresses>=0 && continuousCount==((continuousPresses+1)&0xffffffffL)) listener.continuousPress();
      continuousPresses=continuousCount;
      if (held==0) released=true;
      listener.ptt(released && held==1);
      return true;
    } catch (NumberFormatException e) { return false; }
  }

  private void disconnect() {
    if (connected) listener.disconnected();
    connected=false;
    transport="";
    listener.status("박스 연결 대기 · USB 권한 또는 Wi-Fi 주소 확인");
  }
  private void closeUsb() {
    if (port!=null) try { port.close(); } catch (IOException ignored) { }
    port=null;
    attached=null;
    serialLine.reset();
  }
  void close(){closeUsb();disconnect();}

  private String nonce(int size){byte[] bytes=new byte[size];random.nextBytes(bytes);return hex(bytes);}
  private static String signature(String message,String key) throws Exception {
    Mac mac=Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.US_ASCII),"HmacSHA256"));
    return hex(mac.doFinal(message.getBytes(StandardCharsets.US_ASCII)));
  }
  private static String hex(byte[] array) {
    StringBuilder text=new StringBuilder(array.length*2);
    for(byte value:array) text.append(String.format(Locale.ROOT,"%02x",value&255));
    return text.toString();
  }
}







