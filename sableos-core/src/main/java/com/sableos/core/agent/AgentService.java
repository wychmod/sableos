package com.sableos.core.agent;

import com.sableos.core.profile.Profile;
import com.sableos.core.profile.ProfileContext;
import com.sableos.core.profile.ProfileRegistry;
import com.sableos.core.react.ReActLoop;
import com.sableos.core.session.Session;
import com.sableos.core.session.SessionStore;
import com.sableos.core.trace.TraceContext;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 统一处理入口（FR-011）：三种触发源（CLI / Web / 定时）复用同一入口。
 *
 * <p>职责：取当前 Agent 运行配置 → 放入线程级上下文（供工具执行按当前 Agent 取值）→ 跑循环 → 保存会话 → {@code finally} 清理
 * 线程级上下文（异常路径也必须清，SC-005）。
 *
 * <p>同时用 {@link TraceContext} 把 sessionId/profileName/channel 投影进 MDC 业务键：{@code openIfAbsent()}
 * 保证「外层入口已开 链路则复用、否则新开根链路」（research D-06）。两个作用域与 {@code ProfileContext} 收口于同一处，异常也清。
 */
public final class AgentService {

  /**
   * 会话级串行化锁的分条数（条纹锁）。
   *
   * <p><b>为什么需要</b>：每个请求各自从存储加载独立的 {@link Session} 快照，处理完再整行保存。 同一会话的两个并发请求会各自 append
   * 再各自覆盖，后写盖掉前写——先完成的那一轮对话直接丢失。30 节的 {@code AgentScheduler} 已对此加过会话锁，但 CLI / Web 路径当时漏了。
   *
   * <p><b>为什么用条纹而非按会话 id 建 map</b>：按 id 建的 {@code Map<String, Lock>} 只增不删（会话 id 是无限集），是确定的内存泄露;
   * 而收尾时删锁又会与「同时刻第三个请求刚拿到旧锁」競争。固定条纹数无泄露、无清理逻辑， 代价只是不同会话哈希到同一条时多串行一次——64 条足够稀。
   */
  private static final int LOCK_STRIPES = 64;

  private final ProfileRegistry profileRegistry;
  private final ReActLoop reActLoop;
  private final SessionStore sessionStore;

  /** 条纹锁：按下标取，永不增长，无需清理。 */
  private final ReentrantLock[] sessionLocks = new ReentrantLock[LOCK_STRIPES];

  /**
   * 第 29 节起 {@code ProfileRegistry} 支持运行期 {@code remove}，SpotBugs 据此把它判为可变类型， 于是「构造器持有它」被报
   * EI_EXPOSE_REP2。
   *
   * <p>确认误报：注册表是依赖注入的协作者，本类无任何返回它的 getter、也不对外暴露其内部集合；
   * 共享同一份注册表正是本节的设计目标（启动扫描与运行期新增走同一索引）。撤销条件：一旦本类新增 暴露该字段的 getter 或 public 字段，须重新评估。
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "确认误报：ProfileRegistry 是注入的协作者引用，本类不暴露它也不返回其内部集合；"
              + "共享同一份注册表是第 29 节「启动扫描与运行期新增走同一索引」的设计要求。"
              + "撤销条件：新增暴露该字段的 getter 或 public 字段。")
  public AgentService(
      ProfileRegistry profileRegistry, ReActLoop reActLoop, SessionStore sessionStore) {
    this.profileRegistry = profileRegistry;
    this.reActLoop = reActLoop;
    this.sessionStore = sessionStore;
    for (int i = 0; i < LOCK_STRIPES; i++) {
      sessionLocks[i] = new ReentrantLock();
    }
  }

  /** 取会话对应的条纹锁（哈希取模，稳定且无界不增长）。 */
  private Lock lockFor(String sessionId) {
    return sessionLocks[Math.floorMod(sessionId.hashCode(), LOCK_STRIPES)];
  }

  /**
   * 处理一条用户消息。
   *
   * @param session 本次会话（{@code profileName()} 决定用哪个 Agent 的运行配置）
   * @param userMessage 本条用户消息
   * @return 最终答复文本（透传 {@link ReActLoop#run}）
   * @throws IllegalArgumentException 会话声明的 Agent 未在注册表登记（点名 profile 名）
   */
  public String process(Session session, String userMessage) {
    Profile profile = profileRegistry.get(session.profileName());
    if (profile == null) {
      throw new IllegalArgumentException("未注册的 Agent：" + session.profileName());
    }

    // 同一会话的并发请求必须串行（见 LOCK_STRIPES 注释）：否则后写覆盖前写，丢对话。
    Lock lock = lockFor(session.id());
    lock.lock();
    try {
      // 关键：锁内对齐存储侧最新历史。入口层的 session 是加锁前加载的快照，
      // 若直接拿它跑循环并保存，会把上一个请求刚落库的历史覆盖掉（丢失更新）。
      refreshFromStore(session);
      ProfileContext.set(profile);
      try (TraceContext.Scope ignored = TraceContext.openIfAbsent();
          TraceContext.Scope business =
              TraceContext.attachBusiness(session.id(), profile.name(), session.channel())) {
        String reply = reActLoop.run(session, userMessage, profile);
        sessionStore.save(session);
        return reply;
      } finally {
        ProfileContext.clear();
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * 把入参 session 的历史刷成存储侧最新态（仅当存储实现了读能力时）。
   *
   * <p>不把 {@code SessionStore} 拓宽出 {@code get} 方法：17 节起的只写契约保持不变，运行期传入的是 {@code
   * SessionManager}（{@code JpaSessionManager}），此时才能读到权威快照。只写实现（如单测的假存储）跳过刷新， 行为与修复前一致。
   *
   * @param session 入口层传入的会话实例（可能已过期）
   */
  private void refreshFromStore(Session session) {
    if (sessionStore instanceof com.sableos.core.session.SessionManager manager) {
      manager.get(session.id()).ifPresent(latest -> session.replayHistory(latest.messages()));
    }
  }
}
