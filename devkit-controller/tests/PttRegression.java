package kr.silvertalk.devkit;
final class PttRegression {
  private static void check(boolean ok) { if (!ok) throw new AssertionError(); }
  public static void main(String[] args) {
    PttState p = new PttState();
    p.enable();
    p.context(1,true,false);
    check(p.muted(1));
    p.input(false,2);
    p.input(true,3);
    check(!p.muted(3));
    p.input(false,4);
    check(p.muted(4));
    p.input(true,5);
    p.disconnected();
    check(p.muted(5));
    p.input(true,6);
    check(p.muted(6));
    p.input(false,7);
    p.input(true,8);
    check(!p.muted(8));
    check(p.muted(759));
    System.out.println("PTT start, press, release, disconnect, re-arm and timeout passed");
  }
}
