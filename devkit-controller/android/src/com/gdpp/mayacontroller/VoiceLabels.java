package kr.silvertalk.devkit;

import java.util.*;

/** Strict control names. Never match Dictate, Send, or a generic Close button. */
final class VoiceLabels {
  static String normalize(String value){return value==null?"":value.trim().replaceAll("\\s+"," ").toLowerCase(Locale.ROOT);}
  private static boolean one(String value,String... options){String n=normalize(value);for(String s:options)if(n.equals(s))return true;return false;}
  static boolean sesameStart(String s){return one(s,"start call","통화 시작","음성 통화 시작");}
  static boolean sesameEnd(String s){return one(s,"end call","통화 종료","음성 통화 종료");}
  static boolean start(String s){return one(s,"음성 대화 시작","음성 모드 시작","음성 채팅 시작","start voice","start voice mode","start voice conversation","start a voice chat","start voice chat","voice mode","음성 모드","음성 대화");}
  static boolean end(String s){return one(s,"음성 대화 종료","음성 모드 종료","음성 채팅 종료","end voice","end voice mode","end voice conversation","end voice chat","exit voice mode","end call");}
  static boolean genericEnd(String s){return one(s,"종료","통화 종료","end","exit");}
  static boolean mic(String s){return muteAction(s)||unmuteAction(s);}
  static boolean muteAction(String s){return one(s,"마이크 끄기","음소거","mute","mute microphone","turn off microphone","마이크 음소거");}
  static boolean unmuteAction(String s){return one(s,"마이크 켜기","음소거 해제","unmute","unmute microphone","turn on microphone","마이크 음소거 해제");}
  static boolean connecting(String s){return one(s,"연결 중","연결 중…","연결 중...","다시 연결 중","connecting","connecting…","connecting...","reconnecting");}
  static boolean limit(String s){return one(s,"일일 한도에 도달했습니다","음성 사용 한도에 도달했습니다","일시적으로 음성을 사용할 수 없습니다","daily limit reached","you've reached your daily limit","voice limit reached","voice is temporarily unavailable");}
  static boolean acknowledge(String s){return one(s,"알겠습니다","확인","got it","okay","ok");}
  static boolean piStart(String s){
    return one(s,"call","start call","start a call","start voice call","voice call","call pi","통화 시작","pi와 통화");
  }
  static boolean piEnd(String s){
    return one(s,"end call","hang up","end voice call","통화 종료","음성 통화 종료");
  }
  static boolean startFor(int service,String label){
    if(service==VoiceService.GEMINI)return geminiStart(label);
    if(service==VoiceService.PI)return piStart(label);
    return start(label);
  }
  static boolean endFor(int service,String label){
    if(service==VoiceService.GEMINI)return geminiEnd(label);
    if(service==VoiceService.PI)return piEnd(label);
    return end(label);
  }
  static boolean geminiStart(String s){
    return one(s,"live","go live","start live","start live chat","open gemini live","talk live with gemini",
        "gemini live 시작","live 시작","라이브 시작","라이브로 대화","gemini live 열기","라이브");
  }
  static boolean geminiEnd(String s){
    return one(s,"end live","end live mode","end live chat","exit live","end conversation",
        "live 종료","라이브 종료","live 모드 종료","gemini live 종료","대화 종료");
  }
  static boolean geminiMute(String s,boolean mute){
    return mute
        ?one(s,"mute mic","mute your microphone","마이크 음소거하기","마이크 끄기")
        :one(s,"unmute mic","unmute your microphone","마이크 음소거 해제하기","마이크 다시 켜기");
  }
  static int geminiToggleState(String label,boolean checkable,boolean checked){
    if(!checkable || !one(label,"마이크 전환","toggle microphone","toggle mic"))return -1;
    return checked?1:0;
  }
  static boolean geminiHold(String s){
    return one(s,"hold","hold live","put live on hold","pause live","대기","대기 상태로 전환","live 일시중지");
  }
  static boolean geminiResume(String s){
    return one(s,"resume","resume live","resume live chat","live 다시 시작","live 계속","계속하기","라이브 계속");
  }
  static int micState(int mute,int unmute){
    if(mute==1&&unmute==0)return 0;
    if(mute==0&&unmute==1)return 1;
    return -1;
  }
  static AppSequence.Screen classifyGemini(int starts,int ends,int genericEnds,int mics,boolean connecting,boolean limit,boolean dismiss){
    if(!limit && mics==1 && (ends==1 || (ends==0&&genericEnds==1)))
      return connecting?AppSequence.Screen.CONNECTING:AppSequence.Screen.ACTIVE;
    return classify(starts,ends,genericEnds,mics,connecting,limit,dismiss);
  }
  static AppSequence.Screen classify(int starts,int ends,int genericEnds,int mics,boolean connecting,boolean limit,boolean dismiss){
    if(limit)return AppSequence.Screen.BLOCKED;
    int stops=ends+(mics>0?genericEnds:0);
    if(starts>1||stops>1||(starts>0&&stops>0))return AppSequence.Screen.AMBIGUOUS;
    if(stops==1)return connecting?AppSequence.Screen.CONNECTING:AppSequence.Screen.ACTIVE;
    if(starts==1)return AppSequence.Screen.IDLE;
    return AppSequence.Screen.UNKNOWN;
  }
}







