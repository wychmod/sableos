package com.sableos.storage.memory;

import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

/**
 * {@code memory_entries} 表的 Spring Data JPA 仓库。
 *
 * <p>查询方法严格区分 core / archival，确保截断只作用在归档区（契约二）。
 */
public interface MemoryEntryRepository extends ListCrudRepository<MemoryEntryEntity, Long> {

  @Query(
      "SELECT m FROM MemoryEntryEntity m WHERE m.profileName = :profileName AND m.scope = 'CORE' ORDER BY m.createdAt")
  List<MemoryEntryEntity> findCoreByProfile(@Param("profileName") String profileName);

  @Query(
      value =
          "SELECT * FROM memory_entries WHERE profile_name = :profileName AND scope = 'ARCHIVAL' ORDER BY created_at DESC LIMIT :limit",
      nativeQuery = true)
  List<MemoryEntryEntity> findRecentArchivalByProfile(
      @Param("profileName") String profileName, @Param("limit") int limit);

  @Query(
      value =
          "SELECT * FROM memory_entries WHERE profile_name = :profileName AND scope = 'ARCHIVAL' AND content LIKE :keyword ORDER BY created_at DESC",
      nativeQuery = true)
  List<MemoryEntryEntity> searchArchivalByProfile(
      @Param("profileName") String profileName, @Param("keyword") String keyword);
}
