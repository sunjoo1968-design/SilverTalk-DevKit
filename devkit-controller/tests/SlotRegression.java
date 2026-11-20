package kr.silvertalk.devkit;
final class SlotRegression {
  private static void check(boolean ok){if(!ok)throw new AssertionError();}
  public static void main(String[] args){
    check(VoiceService.slot(VoiceService.DEFAULT_ASSIGNMENTS,0)==VoiceService.SESAME);
    check(VoiceService.slot(VoiceService.DEFAULT_ASSIGNMENTS,1)==VoiceService.GEMINI);
    for(int a=1;a<=4;a++)for(int b=1;b<=4;b++){
      int pair=VoiceService.pack(a,b);
      check(VoiceService.slot(pair,0)==a && VoiceService.slot(pair,1)==b);
    }
    for(int id=1;id<=4;id++){
      check(VoiceService.fromPackage(VoiceService.PACKAGES[id])==id);
      AppSequence sequence=new AppSequence(id);
      check(sequence.selected()==id && sequence.startOrKeep(100));
      check(sequence.step(id,AppSequence.Screen.IDLE,101)==AppSequence.Action.START);
      check(sequence.step(id,AppSequence.Screen.ACTIVE,102)==AppSequence.Action.NONE);
      check(sequence.step(id,AppSequence.Screen.ACTIVE,2103)==AppSequence.Action.NONE && !sequence.busy());
      CallState calls=new CallState();
      calls.observe(id,AppSequence.Screen.ACTIVE,200);
      calls.observe(id,AppSequence.Screen.ACTIVE,2201);
      check(calls.active(id) && calls.anyActive());
      calls.ended(id);
      check(!calls.anyActive());
    }
    check(VoiceLabels.startFor(VoiceService.PI,"Start call"));
    check(VoiceLabels.startFor(VoiceService.CHATGPT,"Start voice mode"));
    check(VoiceLabels.startFor(VoiceService.GEMINI,"Open Gemini Live"));
    check(!VoiceLabels.startFor(VoiceService.PI,"Message"));
    System.out.println("All 16 A/B pairs, four call state paths and service labels passed");
  }
}
