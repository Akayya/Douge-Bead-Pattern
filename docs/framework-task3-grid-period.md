# 任务 3 框架：网格识别支持非整数倍周期

> 分工：本文件是验收方（主会话）写的实现框架。**实现会话**负责按本框架写生产代码 + JVM 单测；
> 验收方负责构建、装机验证、README/交接文档同步、版本号、commit、push。
> 实现会话**不要**改 README.md、docs/交接文档.md、app/build.gradle.kts 的版本号，**不要** git commit/push，
> **不要**起模拟器或碰 adb（装机验证归验收方）。完成后回报：改动文件清单、测试总数与结果、与本框架的偏差。

## 1. 问题

`data/PixelGrid.kt` 的 `period()`（L217–244）只做**整数 lag** 自相关，再用「接近最高分里最小的 p」当基频。
真实周期是 3.3px、8.5px 这类非整数时，整数 lag 的相关分被相位漂移稀释，基频会锁到整数倍/错误 lag，
或整张图判失败。`refinePeriod()`（L194–214）只在整数结果 ±0.8px 内以 0.05 步长重扫，且采样用
`roundToInt(k*p)` 取整列能量——窗口窄 + 取整别名，救不回来。0.6.10 写单测时暴露，生产算法未改。
常见截图缩放（27.3px 等）目前能过，**不能改坏**。

## 2. 目标与验收口径

- 合成像素画周期 ∈ {3.3, 4.5, 8.5, 12.7, 27.3}（含非零相位）能识别，`cellX/cellY` 误差 ≤ 0.1px，cols/rows 正确。
- 照片/平滑噪声仍判 null（现有照片误判回归全绿）；内部条纹（2×/3× 格周期）不锁到谐波。
- 现有 95 个单测全绿；新增用例 ≥ 8 个。
- 性能：240×240 纯算法入口单次 detect 不明显变慢（量级 ≤ 100ms，JVM 上粗测即可）。
- `Grid` data class 的字段与对外语义不变（ImportScreen 依赖 `summary`、confidence 门槛等）。

## 3. 建议方案（coarse-to-fine，可替换但要在回报里说明理由）

1. **粗候选**：保留整数自相关，但不再只取一个基频——收集相关分 ≥ best*0.85 的 p，连同它们的 1/2、1/3
   （谐波消歧候选）一起进候选集。
2. **分数评估改插值**：边界能量曲线按线性插值取值（`edgeAt(x: Float)`），不再 `roundToInt` 取整列。
   候选 p 在 ±1.0px 内以 0.05 步长细扫，评分 = 周期边界处插值能量均值（沿用 `refinePeriod` 的打分形状）。
3. **定基频**：对每个细扫后的候选 p，检查 p/2、p/3 的插值评分；若半频/三分频评分 ≥ p 的 0.9 倍则取更小的
   （真基频优先），否则取评分最高者。阈值是起点，用测试矩阵调。
4. **后续链路不变**：`refinePhase`、`boundaryConcentration`、MIN_CELL=2.8、MIN_CONCENTRATION=1.5、
   conf ≥ 0.30、两方向周期差 ≤ max(1, p/4)、cols/rows ∈ [2,240] 这些门槛全部保留——它们是照片防线。
   `refinePhase` 的采样也建议改插值（小数周期下取整相位会偏）。
5. 大图窗口路径 `detectInWindows` 不改结构，只受益于纯算法入口的改进。

备选（更省但更脆）：只把 `refinePeriod` 窗口放宽到 ±1.5 并改插值采样。若选这条，必须用 8.5px 用例证明
粗候选锁错时也能救回，否则回到方案 3。

## 4. 硬约束（摘自交接文档 §6，违反会编译失败或隐蔽 bug）

- 不引新依赖；生产代码只用 Kotlin stdlib + Android 自带类；org.json 仅测试可用。
- 纯算法放 `data/`，入口保持 `internal fun detect(pixels: IntArray, width: Int, height: Int)` 可 JVM 单测。
- 不 apply kotlin.android 插件（AGP 9.4 内置 Kotlin）；不改 minSdk/compileSdk。
- Bitmap 回收语义不变（`detect(source, crop)` 的 finally recycle）。

## 5. 测试计划（新增 `PixelGridFractionalPeriodTest.kt` 或并入现有测试类）

合成阵列生成器参考现有 `PixelGridDetectorTest.kt` / `TestSupport.kt` 的写法：
每格纯色、格边界 1px 深色线、可加相位偏移与轻微噪声。

1. 3.3 / 4.5 / 8.5 / 12.7 / 27.3 px 周期，相位 0 与非 0，断言 cell 误差 ≤0.1、cols/rows 精确。
2. 各向异性：cellX=8.5、cellY=8.5 与 cellX=6.2、cellY=6.2 各一例。
3. 谐波陷阱：格内画 2× 频率条纹，断言不锁到半周期。
4. 照片防线回归：平滑渐变 + 低幅噪声仍 null（复用现有回归用例的生成方式）。
5. 边界：周期 2.9（<MIN_CELL 应 null）、周期 65（>maxP 应 null 或走窗口路径不崩）。
6. 现有 95 用例全绿是硬门槛。

## 6. 验收方装机验证（实现会话不用做）

- 合成 3.3px 与 8.5px 像素画 PNG（Pillow 生成）推入设备相册 → 图片转图纸 → 应显示「已定位网格 3.3×3.3px 一格」等，预览格数与合成一致。
- 27.3px 旧样例复测不回归；普通照片仍提示「没认出原图格子」。
- 证据截图存 `screenshots/acceptance/`。