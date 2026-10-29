package com.sableos.web.sandbox;

import com.sableos.core.sandbox.SandboxWhitelistAdmin;
import com.sableos.core.sandbox.WhitelistCommand;
import com.sableos.core.sandbox.WhitelistEntry;
import com.sableos.core.sandbox.WhitelistType;
import com.sableos.web.common.ApiResponse;
import com.sableos.web.sandbox.dto.WhitelistEntryRequest;
import com.sableos.web.sandbox.dto.WhitelistEntryResponse;
import com.sableos.web.sandbox.dto.WhitelistUpdateRequest;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sandbox 白名单管理端点（specs/008-sandbox-whitelist-admin/contracts/rest-api.md）。
 *
 * <p>只依赖 core 的 {@link SandboxWhitelistAdmin} 契约，不认 tool 的实现类——保持 {@code web → core} 单向依赖。
 *
 * <p><b>安全边界</b>：这组端点等价于 root 权限（可加"任意目录"与"解释器"）。核心阶段 Web 层无认证（技术方案 §7.5），启用与否由 {@code
 * sableos.sandbox.admin.enabled} 控制（默认开启）；生产环境须只绑回环或置于认证反向代理之后。
 */
@RestController
@RequestMapping("/api/v1/sandbox/whitelist")
@ConditionalOnProperty(
    name = "sableos.sandbox.admin.enabled",
    havingValue = "true",
    matchIfMissing = true)
@SuppressFBWarnings(
    value = {"EI_EXPOSE_REP2", "SPRING_ENDPOINT"},
    justification =
        "EI_EXPOSE_REP2：admin 是容器内共享单例，构造注入共享同一引用正是意图（本类不返回、不修改其内部状态）。"
            + "SPRING_ENDPOINT：核心阶段 Web 层无认证是既定前提（技术方案 §7.5），本端点的暴露面由部署方式兜底"
            + "（回环绑定或认证反向代理），并由 sableos.sandbox.admin.enabled 提供关闭开关；端点级鉴权属扩展阶段。")
public class SandboxWhitelistController {

  private final SandboxWhitelistAdmin admin;

  public SandboxWhitelistController(SandboxWhitelistAdmin admin) {
    this.admin = admin;
  }

  /** 列表；{@code type} 缺省返回全部，按 type、value 排序。 */
  @GetMapping
  public ApiResponse<List<WhitelistEntryResponse>> list(
      @RequestParam(required = false) WhitelistType type) {
    return ApiResponse.ok(admin.list(type).stream().map(WhitelistEntryResponse::from).toList());
  }

  /** 单条详情。 */
  @GetMapping("/{id}")
  public ApiResponse<WhitelistEntryResponse> get(@PathVariable long id) {
    return ApiResponse.ok(WhitelistEntryResponse.from(admin.get(id)));
  }

  /** 新增；成功返回 201。 */
  @PostMapping
  public ResponseEntity<ApiResponse<WhitelistEntryResponse>> add(
      @RequestBody WhitelistEntryRequest request) {
    WhitelistEntry created =
        admin.add(new WhitelistCommand(request.type(), request.value(), request.description()));
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.ok(WhitelistEntryResponse.from(created)));
  }

  /** 修改值与说明；{@code type} 不可变。请求体若携带 type 会被忽略。 */
  @PutMapping("/{id}")
  public ApiResponse<WhitelistEntryResponse> update(
      @PathVariable long id, @RequestBody WhitelistUpdateRequest request) {
    return ApiResponse.ok(
        WhitelistEntryResponse.from(admin.update(id, request.value(), request.description())));
  }

  /** 删除；用 200 + 空 data 而非 204，保持响应信封形状恒定。 */
  @DeleteMapping("/{id}")
  public ApiResponse<Void> delete(@PathVariable long id) {
    admin.delete(id);
    return ApiResponse.ok(null);
  }
}
