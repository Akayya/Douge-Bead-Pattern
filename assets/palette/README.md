# 色板原始数据（未入库）

App 里真正用的是 `app/src/main/assets/palette/mard221.json` 与 `mard291.json`，这两个文件已经提交在仓库里，**构建时不需要本目录**。

本目录（`assets/palette/raw/`）只是重新生成上面两个 JSON 时的原料，体积约 4.4 MB（含两份 legend.pdf、两份 colors.xlsx），已从仓库与本机删除。

## 数据来源

- 仓库：[HansBug/pindou-color-data](https://github.com/HansBug/pindou-color-data)（MIT License, Copyright (c) 2026 HansBug）
- 里面按品牌分目录，本项目只用到 `mard-221-github/` 与 `mard-291-github/`，每个目录含 `colors.json` / `colors.xlsx` / `legend.pdf`
- 颜色值是**屏幕参考值**，与实物豆子有材质、批次差异；App 内允许校准

## 需要重建时

```powershell
# 1. 下载（raw.githubusercontent 在国内常失败，用 codeload 直链）
curl.exe -L -o palette.tar.gz https://codeload.github.com/HansBug/pindou-color-data/tar.gz/refs/heads/main
tar -xzf palette.tar.gz -C assets/palette/raw --strip-components=0
# 解压后路径应为 assets/palette/raw/pindou-color-data-main/mard-221-github/colors.json

# 2. 生成 App 用的紧凑 JSON（需要本机有 python）
python scripts/build-palette-assets.py
```

生成的 `mard221.json`（约 8 KB）与 `mard291.json`（约 11 KB）覆盖写回 `app/src/main/assets/palette/`。
