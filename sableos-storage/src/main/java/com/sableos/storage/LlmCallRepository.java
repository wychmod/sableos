package com.sableos.storage;

import org.springframework.data.jpa.repository.JpaRepository;

/** llm_calls 仓库：本节只落写入与基础读取（完整审计查询接口属扩展阶段）。 */
public interface LlmCallRepository extends JpaRepository<LlmCall, Long> {}
