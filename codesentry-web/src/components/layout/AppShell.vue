<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { currentTheme, setTheme, themes } from '@/utils/theme'

const route = useRoute()
const pageTitle = computed(() => String(route.meta.title ?? 'CodeSentry'))

const navigation = [
  { label: '仪表盘', to: '/', glyph: '⌁' },
  { label: '代码仓库', to: '/projects', glyph: '◇' },
  { label: '发起扫描', to: '/scans/new', glyph: '↗' },
  { label: '扫描记录', to: '/scans', glyph: '▤' },
  { label: '问题台账', to: '/findings', glyph: '!' },
  { label: '扫描规则', to: '/rules', glyph: '⌗' },
  { label: '整改报告', to: '/reports', glyph: '▱' },
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
          <div class="time-chip"><span>本地时间</span><b>{{ new Date().toLocaleDateString('zh-CN') }}</b></div>
        </div>
      </header>

      <main class="content-stage">
        <RouterView v-slot="{ Component }">
          <Transition name="page" mode="out-in">
            <component :is="Component" />
          </Transition>
        </RouterView>
      </main>
    </section>
  </div>
</template>
