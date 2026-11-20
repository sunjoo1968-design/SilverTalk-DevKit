package kr.silvertalk.devkit;

import static kr.silvertalk.devkit.VoiceService.GEMINI;
import static kr.silvertalk.devkit.VoiceService.SESAME;

final class ServiceSwitchRegression {
  private static void expect(AppSequence sequence,int service,AppSequence.Screen screen,long now,AppSequence.Action expected){
    AppSequence.Action actual=sequence.step(service,screen,now);
    if(actual!=expected)throw new AssertionError("Expected "+expected+" but got "+actual+" at "+now);
  }

  private static AppSequence returningToGemini(){
    AppSequence sequence=new AppSequence(GEMINI);
    sequence.select(SESAME,100,true);
    expect(sequence,GEMINI,AppSequence.Screen.ACTIVE,101,AppSequence.Action.STOP);
    expect(sequence,GEMINI,AppSequence.Screen.IDLE,200,AppSequence.Action.NONE);
    expect(sequence,GEMINI,AppSequence.Screen.IDLE,201,AppSequence.Action.OPEN);
    expect(sequence,SESAME,AppSequence.Screen.IDLE,710,AppSequence.Action.START);
    expect(sequence,SESAME,AppSequence.Screen.ACTIVE,900,AppSequence.Action.NONE);
    if(sequence.busy()||sequence.selected()!=SESAME)throw new AssertionError("Sesame handoff incomplete");

    sequence.select(GEMINI,1100,true);
    expect(sequence,SESAME,AppSequence.Screen.ACTIVE,1101,AppSequence.Action.STOP);
    expect(sequence,SESAME,AppSequence.Screen.IDLE,1400,AppSequence.Action.NONE);
    expect(sequence,SESAME,AppSequence.Screen.IDLE,1401,AppSequence.Action.OPEN);
    expect(sequence,GEMINI,AppSequence.Screen.IDLE,1910,AppSequence.Action.START);
    return sequence;
  }

  public static void main(String[] args){
    AppSequence sequence=returningToGemini();
    // The switch settles back on Gemini before its in-flight start reports ACTIVE.
    sequence.select(SESAME,1930,true);
    sequence.select(GEMINI,1990,true);
    expect(sequence,GEMINI,AppSequence.Screen.ACTIVE,2200,AppSequence.Action.NONE);
    expect(sequence,GEMINI,AppSequence.Screen.ACTIVE,2300,AppSequence.Action.NONE);
    expect(sequence,GEMINI,AppSequence.Screen.ACTIVE,4300,AppSequence.Action.NONE);
    if(sequence.busy()||sequence.selected()!=GEMINI)throw new AssertionError("Gemini handoff incomplete");

    sequence=returningToGemini();
    sequence.select(SESAME,1930,true);
    expect(sequence,GEMINI,AppSequence.Screen.ACTIVE,2200,AppSequence.Action.NONE);
    // The switch can also settle after ACTIVE, while the redirect stop is still pending.
    sequence.select(GEMINI,2250,true);
    expect(sequence,GEMINI,AppSequence.Screen.ACTIVE,2300,AppSequence.Action.NONE);
    expect(sequence,GEMINI,AppSequence.Screen.ACTIVE,4300,AppSequence.Action.NONE);
    if(sequence.busy()||sequence.selected()!=GEMINI)throw new AssertionError("Pending stop was not cancelled");

    sequence=returningToGemini();
    sequence.select(SESAME,1930,true);
    expect(sequence,GEMINI,AppSequence.Screen.ACTIVE,2200,AppSequence.Action.NONE);
    expect(sequence,GEMINI,AppSequence.Screen.ACTIVE,2300,AppSequence.Action.STOP);
    System.out.println("Gemini to Sesame to Gemini completes without stopping the final Gemini call");
  }
}
