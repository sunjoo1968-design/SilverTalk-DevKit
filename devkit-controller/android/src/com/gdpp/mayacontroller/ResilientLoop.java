package kr.silvertalk.devkit;

/** A recoverable task exception must not cancel the next heartbeat. */
final class ResilientLoop implements Runnable {
  interface Scheduler { void after(Runnable task,long delay); }
  interface Failure { void onError(RuntimeException error); }
  private final Runnable task;
  private final Scheduler scheduler;
  private final Failure failure;
  private final long interval;
  private volatile boolean stopped=false;
  ResilientLoop(Runnable task,Scheduler scheduler,Failure failure,long interval){this.task=task;this.scheduler=scheduler;this.failure=failure;this.interval=interval;}
  void stop(){stopped=true;}
  @Override public void run(){
    if(stopped)return;
    try{task.run();}catch(RuntimeException e){failure.onError(e);}
    finally{if(!stopped)scheduler.after(this,interval);}
  }
}

