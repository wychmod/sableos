package com.sableos.storage.sandbox;

import org.springframework.data.jpa.repository.JpaRepository;

/** sandbox_whitelist_seed 的仓库：只需继承来的 {@code existsById}，够判断"是否已导入"。 */
public interface SandboxWhitelistSeedRepository
    extends JpaRepository<SandboxWhitelistSeedEntity, Short> {}
