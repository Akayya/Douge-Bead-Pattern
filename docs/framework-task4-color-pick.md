# 任务 4 框架：校准取色——照片吸管

> 分工：本文件是验收方（主会话）写的实现框架。**实现会话**负责按本框架写生产代码 + JVM 单测；
> 验收方负责构建、装机验证、README/交接文档同步、版本号、commit、push。
> 实现会话**不要**改 README.md、docs/交接文档.md、app/build.gradle.kts 的版本号，**不要** git commit/push，
> **不要**起模拟器或碰 adb（装机验证归验收方）。完成后回报：改动文件清单、测试总数与结果、与本框架的偏差。

## 1. 问题与范围

色板校准目前只能手输 6 位 HEX（`ui/palette/PaletteScreen.kt` 校准对话框 L504–580：
`calibrateText` 状态 L97、输入框 L539、确认走 `store.calibrate(code, normalized)` L566）。
本批加**照片吸管**：校准对话框里选一张实物照片 → 点图取色 → HEX 自动回填，走现有预览/确认链路。
**不做**：按色系批量校准、HSV 滑条取色器（留后续批次）。
**注意**：Android 没有公开的"系统取色器 Intent"，别去找/编一个；取色界面自建。

## 2. 目标与验收口径

- 长按色块 → 校准对话框 → 「从照片取色」→ PhotoPicker 选图 → 对话框内显示图 → 点任意像素 →
  输入框回填该像素 HEX、预览色块同步 → 确认 → 「校」角标与「校准 N 色」出现、库页缩略图变色；
  「恢复默认」后复原。全链路装机走查由验收方做。
- 不新增任何权限（PhotoPicker 免权限，参考 `ui/importsheet/ImportScreen.kt` 的 PickVisualMedia 用法）；不联网。
- 选图后 bitmap 下采样到长边 ≤1024 再显示与取色，防大图 OOM；对话框关闭/换图时 recycle。
- 取色坐标映射：显示尺寸 → 原 bitmap 坐标按比例换算，点击处 1px 取值（不做邻域平均，保持可解释）。
- 现有 95 个单测全绿；新增纯逻辑用例 ≥3 个。

## 3. 建议结构

1. **纯逻辑**（可 JVM 单测）：`data/ColorPick.kt`
   - `fun argbToHex(argb: Int): String`：忽略 alpha，输出大写 6 位 HEX（与 `HexColor.normalize` 的输入约定对齐，先读 `data/HexColor.kt` 确认大小写/前缀约定）。
   - `fun mapTapToPixel(tapX: Float, tapY: Float, viewW: Float, viewH: Float, bmpW: Int, bmpH: Int): Pair<Int, Int>`：
     按 contain/center-crop 的实际显示约定换算并 clamp 到边界；**显示约定要和 UI 里 Image 的 contentScale 一致**，写清注释。
2. **UI**（PaletteScreen.kt 校准对话框内）：
   - 输入框旁加「从照片取色」TextButton；用 `rememberLauncherForActivityResult(PickVisualMedia())` 拿 uri。
   - 解码下采样放 `Dispatchers.IO`（`rememberCoroutineScope`），失败 snackbar/提示文案，不崩。
   - 取色态：对话框内嵌一个固定高度（如 220.dp）的 Image + `pointerInput { detectTapGestures { … } }`；
     点中后 `calibrateText = argbToHex(...)`，其余预览/确认/恢复默认逻辑**一行不改**。
   - 对话框 dismiss 时清 bitmap 引用并 recycle；配置变更（旋转）不保留取色态也可接受，写明即可。
3. 状态尽量局部于对话框 composable，不往 PaletteStore 加字段——校准落盘链路完全复用现状
   （`data/Palette.kt` L280 `calibrate`、L38 `colorsFingerprint` 缩略图失效机制不动）。

## 4. 硬约束（摘自交接文档 §6）

- 不引新依赖；Compose + Material3 现有组件够用；不 apply kotlin.android 插件。
- 纯逻辑放 `data/` 且可 JVM 单测；UI 不写业务规则。
- 不申请权限、不联网、不读 MediaStore 全库（只处理 picker 返回的 uri）。
- 中文 UI 文案，风格跟现有对话框一致（简短、口语）。

## 5. 测试计划

`ColorPickTest.kt`：
1. argbToHex：不透明/半透明/全透明都输出 6 位大写 HEX；黑白边界值。
2. mapTapToPixel：中心点、四角、越界 clamp；view 与 bitmap 比例不同（contain 留边）时映射正确。
3. 与 HexColor.normalize 的往返：argbToHex 输出再 normalize 应等于自身去前缀大写形式。
现有 95 用例全绿是硬门槛。

## 6. 验收方装机验证（实现会话不用做）

- 推一张实物感照片（Pillow 合成色块图即可）入相册 → 走 §2 全链路；截图存 `screenshots/acceptance/`。
- 点图边缘/留边处不崩、HEX 与点中色块一致；取消选图、旋转屏幕各走一次不崩。
- 校准后库页缩略图变色、恢复默认复原（复用 0.6.11 指纹机制的既有走查步骤）。