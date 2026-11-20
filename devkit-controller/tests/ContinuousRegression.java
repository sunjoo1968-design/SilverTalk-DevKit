package kr.silvertalk.devkit;
final class ContinuousRegression {
  private static void check(boolean ok) { if (!ok) throw new AssertionError(); }
  public static void main(String[] args) {
    for(int service:new int[]{1,2}){
      AppSequence call=new AppSequence(service);
      long afterSleep=8000;
      check(call.startOrKeep(afterSleep));
      check(call.step(service,AppSequence.Screen.IDLE,afterSleep+1)==AppSequence.Action.START);
      check(call.step(service,AppSequence.Screen.ACTIVE,afterSleep+200)==AppSequence.Action.NONE);
      check(call.step(service,AppSequence.Screen.ACTIVE,afterSleep+2300)==AppSequence.Action.NONE);
      check(!call.busy());
      PttState mic=new PttState();
      mic.enable();
      check(!mic.beginContinuous());
      mic.context(service,true,false);
      check(mic.muted(10));
      mic.input(false,11);
      check(mic.beginContinuous() && !mic.muted(12));
      mic.input(false,13);
      check(!mic.muted(14));
      mic.cancel();
      check(mic.muted(15) && !mic.continuous());
      mic.input(true,16);
      check(!mic.muted(17));
      mic.input(false,18);
      check(mic.muted(19));
      check(mic.beginContinuous());
      mic.disconnected();
      check(!mic.continuous() && mic.muted(20));
      check(mic.beginContinuous());
      mic.context(3-service,true,false);
      check(!mic.continuous() && mic.muted(21));
    }
    check(VoiceLabels.sesameStart("Start call") && VoiceLabels.sesameStart("통화 시작"));
    check(VoiceLabels.start("Start voice mode") && VoiceLabels.start("음성 대화 시작"));
    check(!VoiceLabels.start("Dictate"));
    System.out.println("PASS: both service start paths, mute, PTT, continuous and disconnect");
  }
}
