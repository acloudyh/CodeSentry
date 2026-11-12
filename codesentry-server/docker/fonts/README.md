# 报告字体

当前 PDF 渲染、Word 字体嵌入和 Dockerfile 使用 `simsun.ttf`（宋体）与 `simhei.ttf`（黑体），这两个文件已纳入版本管理。

报告字体可通过 `CODESENTRY_REPORT_SONG_FONT`、`CODESENTRY_REPORT_HEI_FONT` 指定。额外复制到本目录的微软雅黑、楷体、仿宋、华文字体及字体索引暂未被报告代码或镜像引用，保留本地并由 `.gitignore` 排除，不作为本次发布依赖。需要切换字体时，应同时调整渲染、Word 嵌入、镜像复制配置并验证导出结果。
