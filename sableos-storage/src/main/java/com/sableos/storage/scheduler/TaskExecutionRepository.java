package com.sableos.storage.scheduler;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** task_executions 表仓储（第 28 节）。 */
public interface TaskExecutionRepository extends JpaRepository<TaskExecutionEntity, Long> {

  /** 某任务的执行历史，最新在前。 */
  List<TaskExecutionEntity> findByScheduleIdOrderByStartedAtDescIdDesc(Long scheduleId);
}
