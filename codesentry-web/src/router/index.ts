import { ref } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import DashboardView from '@/views/DashboardView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'dashboard', component: DashboardView, meta: { title: '仪表盘' } },
    { path: '/business-projects', name: 'business-projects', component: () => import('@/views/BusinessProjectsView.vue'), meta: { title: '项目概览' } },
    { path: '/business-projects/:id', name: 'business-project-detail', component: () => import('@/views/BusinessProjectDetailView.vue'), meta: { title: '项目详情' } },
    { path: '/projects', name: 'projects', component: () => import('@/views/ProjectsView.vue'), meta: { title: '扫描资产' } },
    { path: '/scans/new', name: 'new-scan', redirect: to => ({ path: '/projects', query: { ...to.query, scan: '1' } }) },
    { path: '/scans', name: 'scan-runs', component: () => import('@/views/ScanRunsView.vue'), meta: { title: '扫描记录' } },
    { path: '/scans/:id', name: 'scan-run-detail', component: () => import('@/views/ScanRunDetailView.vue'), meta: { title: '扫描详情' } },
    { path: '/findings', name: 'findings', component: () => import('@/views/FindingsView.vue'), meta: { title: '问题台账' } },
    { path: '/rules', name: 'rules', component: () => import('@/views/RulesView.vue'), meta: { title: '扫描规则' } },
    { path: '/reports/:id/acceptance', name: 'report-acceptance', component: () => import('@/views/ReportAcceptanceView.vue'), meta: { title: '整改验收' } },
    { path: '/reports/scan-runs/:id/view', component: () => import('@/views/ReportViewerView.vue'), meta: { title: '整改报告预览' } },
    { path: '/business-projects/:id/reports/:roundId/view', component: () => import('@/views/ReportViewerView.vue'), meta: { title: '项目报告预览' } },
    { path: '/reports', name: 'reports', component: () => import('@/views/ReportsView.vue'), meta: { title: '整改报告' } },
    { path: '/tools', name: 'tools', component: () => import('@/views/ToolsView.vue'), meta: { title: '工具管理' } },
    { path: '/settings', name: 'settings', component: () => import('@/views/SettingsView.vue'), meta: { title: '系统设置' } },
  ],
  scrollBehavior: () => ({ top: 0 }),
})

export const navigationError = ref('')
// 发布后旧标签页可能引用已替换的异步模块；仅自动重载一次，持续失败时显示可恢复错误。
router.onError((error, to) => {
  const key = `codesentry-route-reload:${to.fullPath}`
  if (/dynamically imported module|module script|Importing a module|Loading chunk/i.test(error.message) && !sessionStorage.getItem(key)) {
    sessionStorage.setItem(key, '1'); window.location.replace(to.fullPath); return
  }
  navigationError.value = '页面加载失败，请刷新后重试。'
})
router.afterEach((to, _from, failure) => {
  if (!failure) { navigationError.value = ''; sessionStorage.removeItem(`codesentry-route-reload:${to.fullPath}`) }
  document.title = `${String(to.meta.title ?? 'CodeSentry')} · CodeSentry`
})

export default router
