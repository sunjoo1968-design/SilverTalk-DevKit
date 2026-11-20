package kr.silvertalk.devkit;
final class GeminiToggleRegression {
  public static void main(String[] args){
    if(VoiceLabels.geminiToggleState("마이크 전환",true,false)!=0 ||
       VoiceLabels.geminiToggleState("마이크 전환",true,true)!=1 ||
       VoiceLabels.geminiToggleState("마이크 전환",false,true)!=-1 ||
       VoiceLabels.geminiToggleState("카메라 전환",true,true)!=-1)
        throw new AssertionError();
    System.out.println("Gemini observed checkable microphone states pass");
  }
}
