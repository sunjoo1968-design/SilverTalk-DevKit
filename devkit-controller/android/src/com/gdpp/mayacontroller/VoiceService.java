package kr.silvertalk.devkit;

final class VoiceService {
  static final int SESAME=1, GEMINI=2, CHATGPT=3, PI=4, COUNT=4;
  static final int DEFAULT_ASSIGNMENTS=SESAME|(GEMINI<<4);
  static final String GOOGLE_APP="com.google.android.googlequicksearchbox";
  static final String[] TITLES={"","Sesame","Gemini Live","ChatGPT","Pi"};
  static final String[] PACKAGES={"","com.sesame.consumer","com.google.android.apps.bard",
      "com.openai.chatgpt","ai.inflection.pi"};

  static boolean valid(int service){return service>=SESAME&&service<=COUNT;}
  static int slot(int pair,int index){
    int service=index==0?pair&15:(pair>>4)&15;
    return valid(service)?service:(index==0?SESAME:GEMINI);
  }
  static int pack(int a,int b){
    if(!valid(a)||!valid(b))throw new IllegalArgumentException("서비스를 다시 선택하세요.");
    return a|(b<<4);
  }
  static int fromPackage(String name){
    if(GOOGLE_APP.equals(name))return GEMINI;
    for(int i=1;i<=COUNT;i++)if(PACKAGES[i].equals(name))return i;
    return 0;
  }
}
