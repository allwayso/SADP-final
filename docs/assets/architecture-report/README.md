# 课程报告 UML 图源

正式提交文件是 docs 目录中的综合实践报告 Word 文档。它沿用课程提供的原始模板，原模板保持不变。正文中的“一图二表”为 UML 视图组、质量属性表和映射关系表；教师评分表不属于架构二表。

图源由 PlantUML 1.2026.10 MIT 版本生成。各图同时保留可编辑的 `.puml`、用于查看的 `.svg` 和嵌入 Word 的高清 `.png`。

- `use-cases`：关键功能用例图。
- `packages`：四模块包图及依赖方向。
- `class-review`：核验服务与 POI 版本端口类图。
- `class-messaging`：通知传输策略类图。
- `sequence-collection`：成功采集与新数据通知顺序图；拒绝定位和补投的规则见报告正文。
- `sequence-dispute`：已分配现场任务的最终裁定顺序图。
- `deployment`：浏览器、固定隧道与 Linux 容器运行节点部署图；外部平台接口见正文和映射关系表。

报告正文及两张原生 Word 表由 `docs/tools/build_practice_report.py` 生成。该脚本从原始课程模板制作副本，保留两节的纸张与页边距、封面结构和教师评分栏，校验未编辑的包部件及模板 SHA-256。

如需重新生成，先安装 Python 的 `python-docx`、`Pillow`、`lxml`，准备 Java、Graphviz 和 PlantUML，再在仓库根目录执行：

```powershell
$figures = Get-ChildItem docs/assets/architecture-report/*.puml | Where-Object Name -ne common.puml | ForEach-Object FullName
java -jar path/to/plantuml-mit-1.2026.10.jar -charset UTF-8 -tsvg $figures
java -jar path/to/plantuml-mit-1.2026.10.jar -charset UTF-8 -tpng -Sdpi=180 $figures
python docs/tools/build_practice_report.py
```

字体采用模板的宋体、仿宋和 Times New Roman，UML 使用 Microsoft YaHei。报告正文及表格可以在 Word 中直接编辑；图可以修改 PlantUML 源文件后重新生成。图片以行内方式插入，没有浮动文本框。排版验收使用 Microsoft Word 只读导出并逐页检查，导出 PDF 和页面图片仅用于内部检查，不作为本次提交结果。
