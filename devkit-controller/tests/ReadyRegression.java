package kr.silvertalk.devkit;

final class ReadyRegression {
    static void check(boolean pass) { if (!pass) throw new AssertionError(); }
    public static void main(String[] args) {
        AppSequence sesame = new AppSequence(2);
        sesame.selectIdle(1);
        check(sesame.selected()==1 && !sesame.busy());
        check(sesame.startOrKeep(100));
        check(sesame.step(0,AppSequence.Screen.UNKNOWN,200)==AppSequence.Action.OPEN);
        check(sesame.step(1,AppSequence.Screen.IDLE,300)==AppSequence.Action.START);
        check(sesame.step(1,AppSequence.Screen.ACTIVE,400)==AppSequence.Action.NONE && !sesame.busy());
        check(sesame.startOrKeep(500));
        check(sesame.step(1,AppSequence.Screen.ACTIVE,600)==AppSequence.Action.NONE && !sesame.busy());
        sesame.selectForReady(2,700);
        check(sesame.step(1,AppSequence.Screen.ACTIVE,800)==AppSequence.Action.STOP);
        check(sesame.step(1,AppSequence.Screen.IDLE,900)==AppSequence.Action.NONE);
        check(sesame.step(1,AppSequence.Screen.IDLE,1000)==AppSequence.Action.OPEN);
        check(sesame.step(2,AppSequence.Screen.IDLE,1600)==AppSequence.Action.NONE && !sesame.busy());
        check(sesame.selected()==2);
        System.out.println("PASS: selected service, start, existing call, switch without auto-start");
    }
}
