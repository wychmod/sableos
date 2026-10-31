package com.sableos.storage.notify;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** notify_channels 表仓储（第 28 节）。 */
public interface NotifyChannelRepository extends JpaRepository<NotifyChannelEntity, String> {

  /** 缺省渠道取第一条：按创建时间升序，同刻按名称升序。 */
  List<NotifyChannelEntity> findAllByOrderByCreatedAtAscNameAsc();
}
