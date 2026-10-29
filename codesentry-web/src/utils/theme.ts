import { ref } from 'vue'

export const themes = [
  { id: 'blue', name: '经典蓝', description: '深蓝导航、白色卡片、蓝色按钮，接近参考图色系。', background: '#fafbfc', surface: '#ffffff', accent: '#182c80', rail: '#142b78' },
  { id: 'white', name: '简洁白', description: '纯白面板搭配绿色点缀，清晰、轻量。', background: '#f4f6f8', surface: '#ffffff', accent: '#16764e', rail: '#ffffff' },
  { id: 'warm', name: '暖白', description: '暖白底色搭配棕色点缀，柔和、沉稳。', background: '#f6f3ed', surface: '#fffdfa', accent: '#85592c', rail: '#fffdfa' },
  { id: 'cool', name: '冷灰白', description: '冷灰白底色搭配蓝色点缀，简洁、专业。', background: '#f2f5fa', surface: '#ffffff', accent: '#235ec2', rail: '#ffffff' },
] as const

export type ThemeId = typeof themes[number]['id']
// New palette release defaults existing browsers to the requested reference style once.
const preferenceKey = 'codesentry-theme-v2'
export const currentTheme = ref<ThemeId>('blue')

export function setTheme(value: string) {
  const selected = themes.find(item => item.id === value)?.id ?? 'blue'
  currentTheme.value = selected
  document.documentElement.dataset.theme = selected
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content',
    themes.find(item => item.id === selected)!.surface)
  try { localStorage.setItem(preferenceKey, selected) } catch { /* Private browsing may deny storage. */ }
}

export function initializeTheme() {
  let saved = 'blue'
  try { saved = localStorage.getItem(preferenceKey) ?? 'blue' } catch { /* Use the default. */ }
  setTheme(saved)
}
