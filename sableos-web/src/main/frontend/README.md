# SableOS 管理台（Admin Console）

SableOS 的控制面前端：只读为主 + 会话轻交互的管理台。所有业务数据都来自后端 10 个 REST 端点，前端不内置 mock 数据，也不提供后端尚未实现的能力入口。

- 技术栈：Vue 3 + Vite + TypeScript（`strict`）+ vue-router
- 页面：总览、会话、Agents、工具、记忆
- 构建基址：`/admin/`
- 产物目录：`sableos-web/src/main/resources/static/admin/`

## 开发

```bash
cd sableos-web/src/main/frontend
npm ci
npm run dev
```

开发服务器默认监听 `http://localhost:5173`，`/api` 请求通过 Vite 代理转发到 `http://localhost:8080`，因此本地联调不需要处理跨域或 CORS。

接口基址由 `VITE_API_BASE` 控制，默认 `/api/v1`。需要指向其他地址时，在 `.env.local` 中覆盖：

```bash
VITE_API_BASE=/api/v1
```

## 构建

```bash
cd sableos-web/src/main/frontend
npm ci
npm run build
```

构建前会先执行 `vue-tsc --noEmit` 做类型检查，随后由 Vite 产出静态资源到 `sableos-web/src/main/resources/static/admin/`。

后端已提供 `/admin` 静态资源托管与 SPA 回落（`AdminConsoleConfiguration`）：

- `/admin` 302 到 `/admin/`，`/admin/` 返回 `index.html`；
- 所有无扩展名的 `/admin/**` 路径回落到 `index.html`，由前端 History 路由接管；
- `/admin/assets/**` 的指纹资源可长期缓存，入口页 `no-store`，发布后立即生效；
- 只接管 `/admin/**`，不影响 `/api/**` 与 Swagger UI。

本地开发仍推荐 `npm run dev` + Vite 代理，改完前端后执行 `npm run build` 重新生成 `static/admin`，再打包后端即可。

## 目录结构

```
src/
  api/          client.ts（统一信封解析 / 超时 / ApiError）、endpoints.ts（10 个端点）
  components/   布局、图标、状态徽标、骨架屏、对话框、诊断抽屉等共享组件
  composables/  Agent 清单、系统状态、全局刷新、Toast / 诊断状态
  pages/        5 个路由页面
  router/       vue-router 5 条路由
  styles/       设计 token 与全局样式（颜色、字号、间距、动效、表单、状态）
  types/        后端契约的 TypeScript 类型
  utils/        时间格式化、剪贴板
```

## 已接入端点

统一前缀 `/api/v1`，成功与失败共用响应信封 `{ code, message, data, timestamp, traceId }`。

| 方法 | 路径 | 用途 | 页面 |
| --- | --- | --- | --- |
| POST | `/sessions` | 建会话（幂等，同 profile + userId 复用） | 会话 |
| POST | `/sessions/{id}/messages` | 发送消息（非流式） | 会话 |
| GET | `/sessions/{id}` | 查询会话与最近历史 | 会话 |
| DELETE | `/sessions/{id}` | 软归档会话 | 会话 |
| POST | `/agents/{name}/invoke` | 无状态调用 Agent | Agents |
| GET | `/profiles` | Agent 清单 | 会话 / Agents / 记忆 |
| GET | `/memory?profile=...` | 长期记忆全文 | 记忆 |
| GET | `/tools` | 工具清单 | 工具 |
| GET | `/health` | 存活检查 | 全局 / 总览 |
| GET | `/info` | 系统信息与 Provider 连通状态 | 全局 / 总览 |

约定与实现细节：

- 会话 id 形如 `web:alice:default`，进入路径参数前统一 `encodeURIComponent`。
- 只有 HTTP 2xx 且 `code = 0` 视为成功；其余情况解析信封后抛出包含 `httpStatus / code / message / traceId` 的错误。
- 错误文案直接展示后端 `message`，不做二次翻译；`traceId` 同时出现在区块内与可折叠的诊断面板中。
- 超时：普通查询 15 秒，`GET /info` 20 秒，发消息与 Agent 试运行 90 秒（大于后端 60 秒，保证能收到 504）。
- 会话历史最多返回最近 100 条，页面已在时间线顶部标注。
- 归档为软归档，归档后仍可查看历史，后端也不会阻止继续发消息；页面按此语义提示。

## 不包含的能力

以下能力后端当前不存在，前端不做实现，也不放置“敬请期待”的假入口：

- 会话列表 / 分页 / 服务端搜索（只支持按 ID 打开与 localStorage 最近 8 条）
- 长期记忆的写入、编辑、清空、检索
- Agent 的新增、修改、删除、文件上传
- 定时任务 / schedules
- 登录、注册、API Key、JWT、RBAC、多租户
- SSE / WebSocket / 流式输出
- Prometheus 指标、审计查询、Token 统计
- Webhook 触发、工具调用历史、LLM 调用历史

## 视觉规范

- 纯黑底（`#000`）+ `#0d0d0d` / `#111` 面板 + `1px solid #222` 分层，不使用渐变、玻璃拟态与发光阴影。
- 橙色 `#f97316` 只用于激活导航、关键数值、链接与焦点环，不做大面积铺色。
- 正文 Inter / 系统中文字体，ID、JSON、工具名使用等宽字体；所有 token 集中在 `src/styles/tokens.css`。
- 状态同时以文字与颜色表达；所有交互元素有可见焦点环，动效遵循 `prefers-reduced-motion`。
- 响应式：≥ 1200px 完整侧栏，768–1199px 图标栏 + 悬浮提示，< 768px 抽屉导航，会话页改为上下布局。
