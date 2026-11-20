package kr.silvertalk.devkit;
final class GeminiRegression {
  private static void check(boolean ok){if(!ok)throw new AssertionError();}
  public static void main(String[] args){
    check(VoiceLabels.geminiStart("Open Gemini Live"));
    check(VoiceLabels.geminiStart("Live"));
    check(!VoiceLabels.geminiStart("Microphone"));
    check(!VoiceLabels.geminiStart("Start voice mode"));
    check(VoiceLabels.geminiEnd("End Live"));
    check(VoiceLabels.muteAction("음소거") && VoiceLabels.unmuteAction("음소거 해제"));
    check(VoiceLabels.classifyGemini(1,1,0,1,false,false,false)==AppSequence.Screen.ACTIVE);
    AppSequence app=new AppSequence(2);
    check(app.startOrKeep(100));
    check(app.step(2,AppSequence.Screen.IDLE,101)==AppSequence.Action.START);
    check(app.step(2,AppSequence.Screen.ACTIVE,102)==AppSequence.Action.NONE);
    check(app.step(2,AppSequence.Screen.ACTIVE,2102)==AppSequence.Action.NONE && !app.busy());
    check(VoiceLabels.sesameStart("Start call"));
    System.out.println("Gemini Live label, START and session recognition checks passed");
  }
}
