package com.sableos.storage.sandbox;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** sandbox_whitelist_entries 的仓库。排序在 SQL 层做，避免调用方各自排序导致顺序不一致。 */
public interface SandboxWhitelistEntryRepository
    extends JpaRepository<SandboxWhitelistEntryEntity, Long> {

  Optional<SandboxWhitelistEntryEntity> findByTypeAndValue(String type, String value);

  List<SandboxWhitelistEntryEntity> findAllByOrderByTypeAscValueAsc();

  List<SandboxWhitelistEntryEntity> findAllByTypeOrderByValueAsc(String type);

  long countByType(String type);
}
