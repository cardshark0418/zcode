# 实战教训（MasterGo 驾驶舱还原）

在 `amastergo`「模板1」循环中验证过的高价值点。改 `build_from_dsl.py` 时优先对照。

## 布局与 path

- 页坐标 = 祖先链 `relativeX/Y` 累加；容器用绝对定位，不要用 flex 猜大屏布局
- path 可能在本地 `0..w,0..h`，也可能在父坐标系；`choose_viewbox` 要三分法
- 近方形且 path bbox 远大于 layout 的合并矢量（甜甜圈）→ 试 `preserveAspectRatio: xMidYMid slice`
- 复合环（多个 M 子路径）实心填充 → `fill-rule=" evenodd"`

## 填充与效果

- Mask 组：白 silhouette → SVG `clipPath`，不要当白色 paint
- 多层 paint：CSS 多背景 + mask 对「单段/父对齐 chrome」有时更准；失败时退回 inline SVG
- `mix-blend-mode: screen`：仅用于亮 wash / 暗芯描边类；球体主体乱用会吹成白斑
- 多个 `box-shadow` 必须逗号合并到一条声明（分开写会互相覆盖）
- 渐变 stop >100% 要 clamp；`rgba(..., NaN)` / `NaN%` 要按「多停球体≈不透明 / 软光晕≈半透明」区分兜底

## 资源与字体

- 图片 LAYER 用官方 URL 下载；注意 `rotate` + `rotateX:180` → 合成 `scaleX(-1)` 等
- 缺字会严重抬高 mismatch；能嵌 D-DIN / 标题黑就嵌

## 循环策略

- 主指标用失配像素占比（阈值约 30），不是只看 mean abs
- 每轮只打 1～3 个热点；改完重建再测；记录 `qa/round-NN-report.json`
- 回归时立刻回退该改动（例如激进 NaN alpha、blanket screen）
- 收敛 ≠ 达标：FINAL 写清历史最优与剩余限制
