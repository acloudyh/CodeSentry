import { createRouter, createWebHistory } from 'vue-router'
import DashboardView from '@/views/DashboardView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'dashboard', component: DashboardView, meta: { title: '仪表盘' } },
    { path: '/projects', name: 'projects', component: () => import('@/views/ProjectsView.vue'), meta: { title: '扫描项目' } },
    { path: '/scans/new', name: 'new-scan', component: () => import('@/views/NewScanView.vue'), meta: { title: '发起扫描' } },
    { path: '/scans', name: 'scan-runs', component: () => import('@/views/ScanRunsView.vue'), meta: { title: '扫描记录' } },
    { path: '/scans/:id', name: 'scan-run-detail', component: () => import('@/views/ScanRunDetailView.vue'), meta: { title: '扫描详情' } },
    { path: '/findings', name: 'findings', component: () => import('@/views/FindingsView.vue'), meta: { title: '问题台账' } },
    { path: '/rules', name: 'rules', component: () => import('@/views/RulesView.vue'), meta: { title: '扫描规则' } },
    { path: '/reports/:id/acceptance', name: 'report-acceptance', component: () => import('@/views/ReportAcceptanceView.vue'), meta: { title: '整改验收' } },
    { path: '/reports', name: 'reports', component: () => import('@/views/ReportsView.vue'), meta: { title: '整改报告' } },
    { path: '/tools', name: 'tools', component: () => import('@/views/ToolsView.vue'), meta: { title: '工具管理' } },
    { path: '/settings', name: 'settings', component: () => import('@/views/SettingsView.vue'), meta: { title: '系统设置' } },
  ],
  scrollBehavior: () => ({ top: 0 }),
})

router.afterEach((to) => {
  document.title = `${String(to.meta.title ?? 'CodeSentry')} · CodeSentry`
})

export default router
