package kr.silvertalk.devkit;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** At most one slow accessibility operation; results are returned to the controller thread. */
final class UiLane implements AutoCloseable {
  interface Result<T> { void complete(T value,Exception error); }
  private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"MayaAccessibility");t.setDaemon(true);return t;});
  private final Executor callback;
  private final AtomicBoolean busy=new AtomicBoolean();
  private volatile boolean closed=false;
  UiLane(Executor callback){this.callback=callback;}
  boolean busy(){return busy.get();}
  <T> boolean submit(Callable<T> task,Result<T> result){
    if(closed||!busy.compareAndSet(false,true))return false;
    try{
      worker.execute(()->{
        T value=null;Exception failure=null;
        try{value=task.call();}catch(Exception e){failure=e;}
        final T completed=value;final Exception error=failure;
        callback.execute(()->{busy.set(false);if(!closed)result.complete(completed,error);});
      });
      return true;
    }catch(RejectedExecutionException e){busy.set(false);return false;}
  }
  @Override public void close(){closed=true;worker.shutdownNow();}
}

