/** 清除下载文件名中的路径分隔符与控制字符，避免保留用户输入中的目录结构。 */
export function safeFileName(value: string): string {
  return [...value.replace(/[\\/:*?"<>|]/g, '_')]
    .map((character) => {
      const point = character.codePointAt(0) ?? 0
      return point < 32 || point === 127 ? '_' : character
    })
    .join('')
    .trim()
}
