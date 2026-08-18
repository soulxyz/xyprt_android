# Responsive UI 维护约束

口袋小印不假定固定手机宽度。主页面需要同时适应窄屏手机、普通手机、横屏、平板和可调整窗口。

## 当前策略

- 首页内容最大宽度约束并在宽屏居中，避免内容只挤在左侧；打印主入口普通宽度为双列，极窄屏自动单列；文档列表使用 adaptive grid。
- 打印机入口仍放 TopAppBar 右侧，但必须同时可感知状态：未连接显示“未连接”；连接后显示打印机名称，并在可用时显示电量。
- 共创计划、增强识别、设置、历史等内容型页面使用全可用宽度 + 合理 `maxWidth` 居中，不使用“固定窄卡片贴左边”。
- 弹窗使用 viewport 比例 + `widthIn(max=...)`，而不是固定绝对宽度。

## 全局检查规则

固定 `size()` 用于图标、按钮触控目标没有问题；固定 `.width()` 若承担页面/卡片容器宽度则必须重新评估。新增页面优先让父容器负责居中，再让内容先设置最大宽度、后占满该约束：

```text
父 Box: fillMaxSize + Alignment.TopCenter
内容: widthIn(max = pageMaxWidth) -> fillMaxWidth / fillMaxSize
```

Compose 的 Modifier 顺序会影响约束传播；不要写成 `fillMaxWidth().widthIn(max=...)` 或 `fillMaxSize().widthIn(max=...)`，否则 max-width 可能失去预期效果。

列表/网格优先使用 `GridCells.Adaptive`、`BoxWithConstraints` 或少量明确 breakpoint，而不是根据某一台手机写死列宽。

窄屏的最低目标是不截字、不遮按钮、不让 TopAppBar 状态压住标题；宽屏的最低目标是不把所有内容缩成左侧一小块，也不把文本无限拉成长行。


维护时可运行：

```bash
python3 tools/audit-responsive-ui.py
```

它不是截图测试的替代品，而是用于防止本轮已经修掉的“固定窄列贴左 / max-width 顺序失效 / 顶栏打印机状态丢失”类源码回归。
