package com.sableos.core.trace;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * {@link ExecutorService} 的链路传播装饰器：任务在**提交线程**捕获链路快照，在执行线程安装、执行后恢复。
 *
 * <p>只覆写 {@link #execute(Runnable)}：继承 {@link AbstractExecutorService} 后，{@code submit} / {@code
 * invokeAll} / {@code invokeAny} 都由基类包成 {@code FutureTask} 再走 {@code execute}，因此这一处包装即覆盖全部提交入口。
 *
 * <p>继承 {@code AbstractExecutorService} 而非直接 {@code implements ExecutorService}，还有一个工程原因：阿里 P3C 的
 * {@code ServiceOrDaoClassShouldEndWithImplRule} 见到「实现了以 Service 结尾的接口」就要求类名以 Impl 结尾， 对 JDK 接口是误报。
 *
 * <p>由 {@link TracedExecutors#wrap(ExecutorService)} 创建，不单独对外暴露。
 */
final class TracedExecutorService extends AbstractExecutorService {

  private final ExecutorService delegate;

  TracedExecutorService(ExecutorService delegate) {
    this.delegate = delegate;
  }

  @Override
  public void execute(Runnable command) {
    delegate.execute(TracedExecutors.wrapRunnable(command));
  }

  @Override
  public void shutdown() {
    delegate.shutdown();
  }

  @Override
  public List<Runnable> shutdownNow() {
    return delegate.shutdownNow();
  }

  @Override
  public boolean isShutdown() {
    return delegate.isShutdown();
  }

  @Override
  public boolean isTerminated() {
    return delegate.isTerminated();
  }

  @Override
  public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    return delegate.awaitTermination(timeout, unit);
  }

  @Override
  public void close() {
    delegate.close();
  }
}
