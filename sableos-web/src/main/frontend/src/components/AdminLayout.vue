<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import AppIcon, { type IconName } from './AppIcon.vue'
import DiagnosticDrawer from './DiagnosticDrawer.vue'
import ToastStack from './ToastStack.vue'
import { signalRefresh } from '../composables/useRefreshSignal'
import {
  healthState,
  lastRefreshedAt,
  refreshSystem,
  systemInfo,
} from '../composables/useSystemStatus'
import { pushToast, reportError } from '../composables/useUiFeedback'
import { locale, t, toggleLocale } from '../i18n'
import { formatClock } from '../utils/format'

interface NavItem {
  label: string
  path: string
  icon: IconName
}

// 必须在 computed 里取 t()：放在普通数组里只会算一次，切语言时导航不跟着变。
const navItems = computed<NavItem[]>(() => [
  { label: t('总览'), path: '/', icon: 'overview' },
  { label: t('会话'), path: '/sessions', icon: 'sessions' },
  { label: 'Agents', path: '/agents', icon: 'agents' },
  { label: t('定时任务'), path: '/schedules', icon: 'clock' },
  { label: t('工具'), path: '/tools', icon: 'tools' },
  { label: t('记忆'), path: '/memory', icon: 'memory' },
])

const route = useRoute()
const sidebarOpen = ref(false)
const refreshing = ref(false)
const menuButton = ref<HTMLButtonElement | null>(null)
const sidebar = ref<HTMLElement | null>(null)

const currentTitle = computed(() =>
  typeof route.meta.title === 'string' ? t(route.meta.title) : t('控制台'),
)

const versionText = computed(() =>
  systemInfo.value?.version ? `v${systemInfo.value.version}` : t('版本 —'),
)

const healthLabel = computed(() => {
  switch (healthState.value) {
    case 'checking':
      return t('检查中')
    case 'ok':
      return t('运行正常')
    case 'down':
      return t('后端离线')
    default:
      return t('待探测')
  }
})

const lastRefreshTitle = computed(() =>
  systemInfo.value
    ? t('最后刷新 {time}', { time: formatClock(lastRefreshedAt.value) })
    : t('刷新系统状态'),
)

/** 切换按钮上显示的是「点一下会切到哪个语言」，避免用户看不出当前状态。 */
const switchLabel = computed(() => (locale.value === 'zh' ? 'EN' : '中'))
const switchTitle = computed(() => t('切换语言'))

function isActive(path: string): boolean {
  return path === '/' ? route.path === '/' : route.path.startsWith(path)
}

async function handleGlobalRefresh(): Promise<void> {
  if (refreshing.value) return
  refreshing.value = true
  sidebarOpen.value = false
  signalRefresh()
  const result = await refreshSystem()
  const issue = result.infoError ?? result.healthError
  if (issue) {
    reportError(issue, { toast: false })
    pushToast(t('刷新未完全成功，请查看诊断信息'), 'error')
  } else {
    pushToast(t('控制台数据已刷新'), 'success')
  }
  refreshing.value = false
}

function closeSidebar(): void {
  sidebarOpen.value = false
}

watch(
  () => route.fullPath,
  () => {
    sidebarOpen.value = false
  },
)

watch(sidebarOpen, async (open) => {
  await nextTick()
  if (open) {
    sidebar.value?.querySelector<HTMLAnchorElement>('.nav-link')?.focus()
  } else {
    menuButton.value?.focus()
  }
})

onMounted(() => {
  void refreshSystem()
})
</script>

<template>
  <div class="layout" @keydown.esc="closeSidebar">
    <header class="topbar">
      <div class="topbar__identity">
        <button
          ref="menuButton"
          type="button"
          class="icon-button topbar__menu"
          :aria-label="t('打开导航')"
          :aria-expanded="sidebarOpen"
          aria-controls="primary-navigation"
          @click="sidebarOpen = !sidebarOpen"
        >
          <AppIcon :name="sidebarOpen ? 'close' : 'menu'" size="18" />
        </button>

        <RouterLink to="/" class="brand" :aria-label="t('SableOS 管理台首页')">
          <span class="brand__mark" aria-hidden="true">S</span>
          <span class="brand__name">SableOS</span>
          <span class="brand__divider" aria-hidden="true" />
          <span class="brand__product">{{ t('管理台') }}</span>
        </RouterLink>
        <span class="topbar__section">{{ currentTitle }}</span>
      </div>

      <div class="topbar__actions">
        <button
          type="button"
          class="locale-toggle"
          :aria-label="switchTitle"
          :title="switchTitle"
          :data-locale="locale"
          @click="toggleLocale"
        >
          <AppIcon name="language" size="14" />
          <span class="locale-toggle__label">{{ switchLabel }}</span>
        </button>
        <span class="version-chip" :title="versionText">{{ versionText }}</span>
        <span class="health" :class="`health--${healthState}`" :title="healthLabel">
          <span class="status-dot" aria-hidden="true" />
          <span class="health__label">{{ healthLabel }}</span>
        </span>
        <button
          type="button"
          class="btn btn--secondary btn--sm topbar__refresh"
          :disabled="refreshing"
          :aria-label="lastRefreshTitle"
          :title="lastRefreshTitle"
          @click="handleGlobalRefresh"
        >
          <AppIcon name="refresh" size="14" :class="{ spinning: refreshing }" />
          <span class="topbar__refresh-label">{{ refreshing ? t('刷新中') : t('刷新') }}</span>
        </button>
      </div>
    </header>

    <div class="shell">
      <div
        v-if="sidebarOpen"
        class="sidebar-scrim"
        aria-hidden="true"
        @click="closeSidebar"
      />

      <aside
        id="primary-navigation"
        ref="sidebar"
        class="sidebar"
        :class="{ 'sidebar--open': sidebarOpen }"
        :aria-label="t('主导航')"
      >
        <div class="sidebar__inner">
          <div class="sidebar__heading">
            <span class="eyebrow">Control plane</span>
            <span class="sidebar__heading-meta">LOCAL / SYS</span>
          </div>

          <nav class="sidebar__nav">
            <RouterLink
              v-for="item in navItems"
              :key="item.path"
              :to="item.path"
              class="nav-link"
              :class="{ 'is-active': isActive(item.path) }"
              :data-tooltip="item.label"
              :aria-current="isActive(item.path) ? 'page' : undefined"
            >
              <AppIcon :name="item.icon" size="18" />
              <span class="nav-link__label">{{ item.label }}</span>
            </RouterLink>
          </nav>
        </div>

        <div class="sidebar__footer">
          <span class="sidebar__footer-mark" aria-hidden="true" />
          <div class="sidebar__footer-copy">
            <span>CORE STAGE</span>
            <span>{{ t('内网 · 无认证') }}</span>
          </div>
        </div>
      </aside>

      <main class="main">
        <slot />
      </main>
    </div>

    <ToastStack />
    <DiagnosticDrawer />
  </div>
</template>

<style scoped>
.layout {
  min-height: 100vh;
  background: var(--bg);
}

.topbar {
  position: fixed;
  z-index: 50;
  top: 0;
  right: 0;
  left: 0;
  display: flex;
  height: var(--topbar-height);
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
  padding: 0 var(--space-6);
  border-bottom: 1px solid var(--border);
  background: var(--bg-topbar);
}

.topbar__identity,
.topbar__actions {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: var(--space-3);
}

.topbar__menu {
  display: none;
}

.brand {
  display: inline-flex;
  align-items: center;
  flex: 0 0 auto;
  gap: var(--space-2);
  color: var(--text);
  text-decoration: none;
}

.brand:hover {
  color: var(--text);
}

.brand__mark {
  display: grid;
  width: 24px;
  height: 24px;
  border: 1px solid var(--accent);
  border-radius: var(--radius-sm);
  color: var(--accent);
  font-family: var(--font-mono);
  font-size: 12px;
  font-weight: 700;
  line-height: 1;
  place-items: center;
}

.brand__name {
  font-size: var(--fs-14);
  font-weight: 650;
  letter-spacing: -0.01em;
}

.brand__divider {
  width: 1px;
  height: 14px;
  background: var(--border-strong);
}

.brand__product {
  color: var(--text-secondary);
  font-size: var(--fs-13);
}

.topbar__section {
  display: inline-flex;
  height: 20px;
  align-items: center;
  padding-left: var(--space-3);
  border-left: 1px solid var(--border);
  color: var(--text-muted);
  font-family: var(--font-mono);
  font-size: var(--fs-11);
  letter-spacing: 0.03em;
  white-space: nowrap;
}

.version-chip {
  display: inline-flex;
  height: 24px;
  align-items: center;
  padding: 0 8px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  color: var(--text-muted);
  font-family: var(--font-mono);
  font-size: var(--fs-11);
  line-height: var(--lh-16);
  white-space: nowrap;
}

.health {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--text-secondary);
  font-size: var(--fs-11);
  line-height: var(--lh-18);
  white-space: nowrap;
}

.health--ok {
  color: #86efac;
}

.health--down {
  color: #fca5a5;
}

.health--checking {
  color: var(--warning);
}

.health--unknown {
  color: var(--text-muted);
}

.topbar__refresh {
  flex: 0 0 auto;
}

.locale-toggle {
  display: inline-flex;
  height: 24px;
  flex: 0 0 auto;
  align-items: center;
  gap: 5px;
  padding: 0 7px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
  font-family: var(--font-mono);
  font-size: var(--fs-11);
  line-height: var(--lh-16);
  white-space: nowrap;
  transition:
    border-color var(--duration-fast) var(--ease-out),
    background-color var(--duration-fast) var(--ease-out),
    color var(--duration-fast) var(--ease-out);
}

.locale-toggle:hover {
  border-color: var(--accent-border);
  background: var(--accent-soft);
  color: var(--accent);
}

.locale-toggle__label {
  min-width: 14px;
  font-weight: 600;
  letter-spacing: 0.02em;
  text-align: center;
}

.spinning {
  animation: spin 700ms linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.shell {
  min-height: 100vh;
  padding-top: var(--topbar-height);
}

.sidebar {
  position: fixed;
  z-index: 40;
  top: var(--topbar-height);
  bottom: 0;
  left: 0;
  display: flex;
  width: var(--nav-width);
  flex-direction: column;
  justify-content: space-between;
  border-right: 1px solid var(--border);
  background: var(--bg-sidebar);
}

.sidebar__inner {
  padding: var(--space-5) var(--space-2) 0;
}

.sidebar__heading {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 0 var(--space-3) var(--space-4);
}

.sidebar__heading-meta {
  color: var(--text-faint);
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.1em;
}

.sidebar__nav {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.nav-link {
  position: relative;
  display: flex;
  height: 40px;
  align-items: center;
  gap: var(--space-3);
  padding: 0 var(--space-3);
  border-radius: var(--radius-sm);
  color: var(--text-secondary);
  text-decoration: none;
  transition:
    background-color var(--duration-fast) var(--ease-out),
    color var(--duration-fast) var(--ease-out);
}

.nav-link::before {
  position: absolute;
  top: 10px;
  bottom: 10px;
  left: 0;
  width: 2px;
  border-radius: var(--radius-pill);
  background: transparent;
  content: '';
}

.nav-link:hover {
  background: #111111;
  color: var(--text);
}

.nav-link.is-active {
  background: var(--panel-soft);
  color: var(--text);
}

.nav-link.is-active::before {
  background: var(--accent);
}

.nav-link.is-active .od-icon {
  color: var(--accent);
}

.nav-link__label {
  min-width: 0;
  overflow: hidden;
  font-size: var(--fs-13);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sidebar__footer {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin: var(--space-4) var(--space-3) var(--space-5);
  padding: var(--space-3);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-sm);
  background: #050505;
}

.sidebar__footer-mark {
  width: 6px;
  height: 6px;
  flex: 0 0 auto;
  border-radius: 50%;
  background: var(--accent);
}

.sidebar__footer-copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 1px;
  color: var(--text-muted);
  font-family: var(--font-mono);
  font-size: 10px;
  line-height: 16px;
}

.sidebar-scrim {
  display: none;
}

.main {
  margin-left: var(--nav-width);
  min-height: calc(100vh - var(--topbar-height));
}

@media (max-width: 1199px) and (min-width: 768px) {
  .sidebar {
    width: var(--nav-width-compact);
  }

  .main {
    margin-left: var(--nav-width-compact);
  }

  .sidebar__inner {
    padding: var(--space-4) var(--space-2) 0;
  }

  .sidebar__heading {
    align-items: center;
    padding: 0 0 var(--space-4);
  }

  .sidebar__heading .eyebrow {
    font-size: 9px;
    letter-spacing: 0.08em;
  }

  .sidebar__heading-meta {
    display: none;
  }

  .nav-link {
    justify-content: center;
    padding: 0;
  }

  .nav-link__label {
    display: none;
  }

  .sidebar__footer {
    justify-content: center;
    margin-right: var(--space-2);
    margin-left: var(--space-2);
    padding: var(--space-3) 0;
  }

  .sidebar__footer-copy {
    display: none;
  }

  .nav-link[data-tooltip] {
    position: relative;
  }

  .nav-link[data-tooltip]::after {
    position: absolute;
    z-index: 60;
    top: 50%;
    left: calc(100% + 10px);
    padding: 4px 7px;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    background: #151515;
    color: var(--text);
    content: attr(data-tooltip);
    font-size: var(--fs-11);
    line-height: var(--lh-16);
    opacity: 0;
    pointer-events: none;
    transform: translateY(-50%) translateX(-3px);
    transition:
      opacity var(--duration-fast) var(--ease-out),
      transform var(--duration-fast) var(--ease-out);
    white-space: nowrap;
  }

  .nav-link[data-tooltip]:hover::after,
  .nav-link[data-tooltip]:focus-visible::after {
    opacity: 1;
    transform: translateY(-50%) translateX(0);
  }
}

@media (max-width: 767px) {
  .topbar {
    padding: 0 var(--space-4);
  }

  .topbar__menu {
    display: inline-flex;
  }

  .topbar__section {
    display: none;
  }

  .version-chip {
    display: none;
  }

  .health__label,
  .topbar__refresh-label {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip: rect(0, 0, 0, 0);
    white-space: nowrap;
  }

  .topbar__refresh {
    width: 32px;
    padding: 0;
  }

  .locale-toggle {
    width: 32px;
    justify-content: center;
    padding: 0;
  }

  .locale-toggle__label {
    display: none;
  }

  .sidebar {
    width: min(280px, calc(100vw - 48px));
    transform: translateX(-100%);
    transition: transform var(--duration-base) var(--ease-out);
  }

  .sidebar--open {
    transform: translateX(0);
  }

  .sidebar__inner {
    padding-top: var(--space-4);
  }

  .sidebar__heading {
    padding-left: var(--space-3);
  }

  .sidebar-scrim {
    position: fixed;
    z-index: 35;
    top: var(--topbar-height);
    right: 0;
    bottom: 0;
    left: 0;
    display: block;
    background: rgba(0, 0, 0, 0.66);
  }

  .main {
    margin-left: 0;
  }
}
</style>
