# Quickstart: Sandbox 白名单管理接口

**Feature**: `008-sandbox-whitelist-admin`

## 前置

```bash
# JDK 21 / Maven 3.9
java -version    # 期望 21.x
mvn -v
```

## 1. 全量门禁

```bash
mvn spotless:apply
mvn clean verify
```

预期：`BUILD SUCCESS`，无 Spotless / Checkstyle / P3C / SpotBugs / PMD 告警。

## 2. 只跑本节测试

```bash
mvn -B -pl sableos-core,sableos-storage,sableos-tool,sableos-web,sableos-boot -am test \
  -Dtest='WhitelistRulesTest,SandboxWhitelistAdminTest,SandboxWhitelistControllerTest,WhitelistSandboxTest,SandboxWhitelistEntryRepositoryTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

## 3. 端到端手工验证

```bash
# 启动（默认开启管理接口）
mvn -pl sableos-cli -am spring-boot:run -Dspring-boot.run.arguments="serve"
# 或 fat jar:
java -jar sableos-boot/target/sableos-boot-*.jar serve
```

### 3.1 新增条目并验证立即生效

```bash
# 新增文件目录白名单
curl -s -X POST http://127.0.0.1:8080/api/v1/sandbox/whitelist \
  -H 'Content-Type: application/json' \
  -d '{"type":"FILE_PATH","value":".sableos/agents","description":"Agent 目录"}' | jq

# 查询
curl -s http://127.0.0.1:8080/api/v1/sandbox/whitelist?type=FILE_PATH | jq
```

### 3.2 验证等价写法判重

```bash
# 以下四条都应返回 409
for v in ".sableos/agents/" ".sableos//agents" ".sableos/./agents" ".sableos/a/../agents"; do
  curl -s -o /dev/null -w "$v -> %{http_code}\n" \
    -X POST http://127.0.0.1:8080/api/v1/sandbox/whitelist \
    -H 'Content-Type: application/json' \
    -d "{\"type\":\"FILE_PATH\",\"value\":\"$v\"}"
done
```

### 3.3 验证非 ASCII 域名被拒

```bash
curl -s -X POST http://127.0.0.1:8080/api/v1/sandbox/whitelist \
  -H 'Content-Type: application/json' \
  -d '{"type":"HTTP_DOMAIN","value":"中文.example.com"}' | jq
# 期望 400，message 含 "只接受 ASCII"
```

### 3.4 验证删光条目后重启不重播

```bash
# 删光
for id in $(curl -s http://127.0.0.1:8080/api/v1/sandbox/whitelist | jq -r '.data[].id'); do
  curl -s -X DELETE "http://127.0.0.1:8080/api/v1/sandbox/whitelist/$id" > /dev/null
done

# 确认空
curl -s http://127.0.0.1:8080/api/v1/sandbox/whitelist | jq '.data | length'   # 期望 0

# 重启进程后再次确认
curl -s http://127.0.0.1:8080/api/v1/sandbox/whitelist | jq '.data | length'   # 仍期望 0
```

### 3.5 验证开关

```bash
mvn -pl sableos-cli -am spring-boot:run \
  -Dspring-boot.run.arguments="serve --sableos.sandbox.admin.enabled=false"
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:8080/api/v1/sandbox/whitelist
# 期望 404
```

## 4. 其余人工项

- 在 Windows / macOS / Linux 各跑一次 3.1~3.3。
- 隔离环境下加 `FILE_PATH=/` + `SHELL_COMMAND=python`，确认启动告警文案出现。

## 5. 预期测试清单

| 测试类 | 模块 | 守点 |
| --- | --- | --- |
| `WhitelistRulesTest` | tool | 三类校验与归一；等价路径收敛；非 ASCII 拒绝 |
| `WhitelistSandboxTest`（扩展） | tool | `refresh` 后立即生效；空策略 deny-all |
| `SandboxWhitelistAdminTest` | tool | CRUD；409/404；快照刷新；删光不重播 |
| `SandboxWhitelistEntryRepositoryTest` | storage | V5 迁移；唯一索引；`CHECK (id=1)`；`entry_count=0` |
| `SandboxWhitelistControllerTest` | web | 201/200/400/404/409；信封 5 字段；400 不回显 |
| `SandboxWhitelistApiIT` | boot | 全链路 + `ToolExecutor` 验证放行/拒绝 |
