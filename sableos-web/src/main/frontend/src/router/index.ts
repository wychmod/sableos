import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'overview',
      component: () => import('../pages/OverviewPage.vue'),
      meta: { title: '系统总览' },
    },
    {
      path: '/sessions',
      name: 'sessions',
      component: () => import('../pages/SessionsPage.vue'),
      meta: { title: '会话' },
    },
    {
      path: '/agents',
      name: 'agents',
      component: () => import('../pages/AgentsPage.vue'),
      meta: { title: 'Agents' },
    },
    {
      path: '/tools',
      name: 'tools',
      component: () => import('../pages/ToolsPage.vue'),
      meta: { title: '工具' },
    },
    {
      path: '/memory',
      name: 'memory',
      component: () => import('../pages/MemoryPage.vue'),
      meta: { title: '记忆' },
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: '/',
    },
  ],
  scrollBehavior: () => ({ top: 0 }),
})

router.afterEach((to) => {
  const title = typeof to.meta.title === 'string' ? to.meta.title : '控制台'
  document.title = `${title} · SableOS 管理台`
})

export default router
