<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { currentTheme, setTheme, themes } from '@/utils/theme'
import { formatChinaDate } from '@/utils/datetime'

const previewLabel = import.meta.env.VITE_CODESENTRY_PREVIEW_LABEL
const originalUrl = import.meta.env.VITE_CODESENTRY_ORIGINAL_URL
const route = useRoute()
const pageTitle = computed(() => String(route.meta.title ?? 'CodeSentry'))

const navigation = [
  { label: '仪表盘', to: '/', glyph: '⌁' },
  { label: '项目概览', to: '/business-projects', glyph: '◇' },
  { label: '扫描资产', to: '/projects', glyph: '▦' },
  { label: '扫描记录', to: '/scans', glyph: '▤' },
  { label: '问题台账', to: '/findings', glyph: '!' },
  { label: '扫描规则', to: '/rules', glyph: '⌗' },
  { label: '整改报告', to: '/reports', glyph: '▱' },
  { label: '工具管理', to: '/tools', glyph: '⌘' },
]
</script>

<template>
  <div class="app-frame">
    <aside class="side-rail">
      <RouterLink to="/" class="brand-lockup" aria-label="CodeSentry 首页">
        <span class="brand-mark"><i></i><b></b></span>
        <span>
          <strong>CODE<span>SENTRY</span></strong>
          <small>本地代码审核系统</small>
        </span>
      </RouterLink>

      <div class="rail-caption">功能导航</div>
      <nav class="primary-nav" aria-label="主导航">
        <RouterLink v-for="item in navigation" :key="item.to" :to="item.to">
          <span class="nav-glyph">{{ item.glyph }}</span>
          <span>{{ item.label }}</span>
        </RouterLink>
      </nav>

      <div class="rail-footer">
        <RouterLink to="/settings" class="settings-link">
          <span class="nav-glyph">⌘</span>
          <span>系统设置</span>
        </RouterLink>
        <div class="system-state">
          <span class="pulse-dot"></span>
          <div><strong>本地节点</strong><small>监听 127.0.0.1</small></div>
        </div>
      </div>
    </aside>

    <section class="app-stage">
      <header class="command-bar">
        <div>
          <span class="eyebrow">代码安全 / {{ pageTitle }}</span>
          <h1>{{ pageTitle }}</h1>
        </div>
        <div class="command-actions">
          <label class="theme-switch"><span>页面主题</span><select :value="currentTheme" aria-label="页面主题" @change="setTheme(($event.target as HTMLSelectElement).value)"><option v-for="theme in themes" :key="theme.id" :value="theme.id">{{ theme.name }}</option></select></label>
          <div class="time-chip"><span>东八区时间</span><b><time class="datetime-text">{{ formatChinaDate() }}</time></b></div>
        </div>
      </header>

      <main class="content-stage">
        <div v-if="previewLabel" class="notice" role="status" style="margin-bottom: 20px">
          <strong>{{ previewLabel }}</strong> · 此页面仅包含独立验证数据。
          <a v-if="originalUrl" :href="originalUrl">打开原服务，查看原有项目与历史记录</a>
        </div>
        <RouterView v-slot="{ Component }">
          <Transition name="page" mode="out-in">
            <component :is="Component" />
          </Transition>
        </RouterView>
      </main>
    </section>
  </div>
</template>
