<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const pageTitle = computed(() => String(route.meta.title ?? 'CodeSentry'))

const navigation = [
  { label: '态势总览', to: '/', glyph: '⌁' },
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
          <small>LOCAL REVIEW SYSTEM</small>
        </span>
      </RouterLink>

      <div class="rail-caption">OPERATIONS</div>
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
          <div><strong>LOCAL NODE</strong><small>监听 127.0.0.1</small></div>
        </div>
      </div>
    </aside>

    <section class="app-stage">
      <header class="command-bar">
        <div>
          <span class="eyebrow">CODE SECURITY / {{ String(route.name ?? '').toUpperCase() }}</span>
          <h1>{{ pageTitle }}</h1>
        </div>
        <div class="command-actions">
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
