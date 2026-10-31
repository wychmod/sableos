package com.sableos.storage.scheduler;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** scheduled_tasks 表仓储（第 28 节）。 */
public interface ScheduledTaskRepository extends JpaRepository<ScheduledTaskEntity, Long> {

  /** 按 Agent + 任务键定位；唯一约束保证至多一条（含退役行）。 */
  Optional<ScheduledTaskEntity> findByProfileNameAndScheduleKey(
      String profileName, String scheduleKey);

  /** 活动任务列表：退役行不参与调度与管理台列表。 */
  List<ScheduledTaskEntity> findByRetiredFalseOrderByProfileNameAscScheduleKeyAsc();
}
