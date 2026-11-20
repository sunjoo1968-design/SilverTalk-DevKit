package kr.silvertalk.devkit;
final class GeminiPttRegression {
  static void check(boolean value){if(!value)throw new AssertionError();}
  public static void main(String[] args){
    check(VoiceLabels.geminiHold("Hold") && VoiceLabels.geminiHold("대기"));
    check(VoiceLabels.geminiResume("Resume Live"));
    check(VoiceLabels.geminiMute("Mute mic",true));
    check(VoiceLabels.geminiMute("마이크 음소거 해제하기",false));
    check(!VoiceLabels.geminiHold("Share screen") && !VoiceLabels.geminiResume("Continue to settings"));
    check(VoiceLabels.micState(1,0)==0 && VoiceLabels.micState(0,1)==1 && VoiceLabels.micState(1,1)==-1);
    check(VoiceLabels.classifyGemini(1,0,1,1,false,false,false)==AppSequence.Screen.ACTIVE);
    check(VoiceLabels.classifyGemini(1,0,2,1,false,false,false)==AppSequence.Screen.AMBIGUOUS);
    check(VoiceLabels.classifyGemini(1,0,0,0,false,false,false)==AppSequence.Screen.IDLE);
    PttState ptt=new PttState();
    ptt.enable();
    ptt.context(VoiceService.GEMINI,true,false);
    check(ptt.muted(10));
    ptt.input(false,11);
    ptt.input(true,12);
    check(!ptt.muted(12));
    ptt.input(false,13);
    check(ptt.muted(13));
    check(VoiceLabels.sesameStart("Start call"));
    System.out.println("Gemini mute, hold/resume, active-state and PTT regressions passed");
  }
}
