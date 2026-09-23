---
name: d2c-loop
description: >-
  Pixel-feedback loop for high-fidelity design-to-code restore (render rules +
  screenshot-diff iteration). Execute ONLY when the user explicitly enables this
  skill (e.g. @d2c-loop, 「启用 d2c-loop」). When the user restores a complex
  frontend page via MCP/MasterGo/Figma without enabling it, briefly recommend
  enabling this skill first—do not auto-run the loop.
---

# 设计稿还原：渲染规则 + 像素闭环

## 触发规则

1. **执行**：仅当用户**明确启用**本 skill（`@d2c-loop`、说「启用 / 按这个 skill 做」）。未启用 → 不要自己开循环。
2. **推荐**：用户要用 MCP（或同类设计源）还原**较复杂**前端页，且还没启用时 → **简短推荐**启用。用户不启用 → 按原要求普通还原。

只丢一个设计链接、说「帮我还原」，缺约束、缺验收、缺循环 → 容易凭感觉画。本 skill 补的就是：**可执行路线 + 可度量验收 + 无人值守**。

## 核心思路

MCP 能读到图层树、宽高坐标、填充/字体、真实文案、SVG 和图片 URL。那是**原料**，不是成品。

复杂页一次「按 MCP 出 HTML」往往差。更稳的是：

- **渲染规则**：可重复地把设计数据变成页面（常见是构建脚本）。优先改规则，不要每轮手改/重写整页。
- **像素闭环**：做出来 → 截图 → 和对照图比 → 修差异最大的区域 → 再测。

Loop 的价值是**可度量、可停止**，不是神话式 100% 像素还原。设计工具和浏览器渲染本就不是同一套；差异够小后每轮可能只动 0.01%，继续烧 token 不划算。有些局部缺陷模型自己发现不了，停下来等人指出即可。

## 这一阶段做什么

先把「长得像」做出来：固定画布静态页（常见 1920×1080），结构 / 文案 / 色值 / 图标跟设计走。默认**不做交互、不上框架**，除非用户另说。

页面构建路线通常是：拉 DSL → 导出原生 SVG/图片 → **按图层/模块**用坐标拼静态 HTML。对照图只用于验收，**不能当页面内容**。

## 约束（必须）

- **禁止整页贴图**：`design-ref.png` / 设计导出的整屏截图 / 整页切图，**一律不得**作为 `<img>`、`background-image` 或唯一子节点铺满画布。那是作弊，差异率会假好，不是还原。
- **必须逐个模块还原成真实 DOM**：顶栏、指标卡、图表、地图、列表各是各的节点，用 DSL 的坐标、文案、色值、path/SVG 搭出来。只允许该图层自己的资源 URL（图标、底图切片）。**禁止**把设计稿按区域截成 `header.png` / `card1.png` 再拼回去——那和整页贴图是一类事。
- **禁止瞎编**：文字/数字、图标、装饰、颜色、渐变、图片，只能来自 DSL / MCP / 设计导出的官方资源。没有的就空着或按结构占位，不要用假数据、手写 path、图表库、占位色块冒充。
- **不要从别的已还原项目拷代码当起点**（用户明确允许除外）。
- 对照图、DSL、页面、截图、diff 都放在**当前项目目录**里，后续脚本才能对着同一套文件跑。
- 循环中途不要停下来问用户；自己根据 diff 决策并继续，直到停止条件。

自检：打开页面如果主体是一张满屏图，或每个模块都是一张切图、几乎没有文字节点，这一版作废，推倒用 DSL 重做。

## 窗口适配（固定画布必做）

笔记本浏览器有标签栏/地址栏，可视区往往不到 1080，`overflow: hidden` 会把下面裁掉——每次还原都会碰到。

约定：

- **舞台写死设计像素**（如 `#stage { width:1920px; height:1080px }`），不要用 `100vh` 当画布高度。
- **预览时整页等比缩小塞进窗口**：`scale = min(innerWidth/画布宽, innerHeight/画布高)`，`transform-origin` 居中或左上。`resize` 时重算。
- 真 1080p 大屏或 Playwright 视口 = 画布时，scale 自然为 1，不影响差异对比。
- 不要靠改模块坐标「往上挤」来迁就浏览器窗口。

```html
<style>
html, body { margin:0; height:100%; overflow:hidden; background:#000; }
#stage { width:1920px; height:1080px; position:absolute; left:50%; top:50%; transform-origin:center center; }
</style>
<script>
function fit() {
  const stage = document.getElementById('stage');
  const s = Math.min(innerWidth / 1920, innerHeight / 1080);
  stage.style.transform = `translate(-50%,-50%) scale(${s})`;
}
addEventListener('resize', fit); fit();
</script>
```

画布不是 1920×1080 时，把两个数字换成设计稿尺寸即可。

## 对照图入库（先做这件事）

后续 Playwright 截图要和设计稿比，所以 **AI 必须自己把设计整页截图放进项目**：

1. 用户已放入的图 → 复制到项目内固定位置。
2. 没有现成文件 → 用设计工具/MCP 能拿到的导出或截图，存**整页、画布尺寸**的图。
3. 建议路径：`design-data/design-ref.png`。这份图**只给差异脚本用**，不要写进 `index.html`。

没有对照图，不要空跑闭环。

## 循环

```
拿资源 → 构建页面 → Playwright 截图 → 跑差异脚本 → 修差异最大分区 → 再构建…
```

1. **拿资源**：拉完整 DSL，导出 path/SVG、官方图片、字体（有文件就嵌），落到项目里。
2. **构建页面**：可重复生成静态页；按模块绝对定位拼出来，不要用 flex 猜布局，**不要贴整页对照图、也不要按区切图拼**。固定画布页**必须做窗口适配**（见上），否则用浏览器打开会底下被裁。
3. **Playwright 截图**：视口 = 画布尺寸；优先本地 HTTP（`file://` 下 mask/字体容易坏）；等字体和图片加载完。截图放项目里，如 `qa/round-N-shot.png`。适配缩放在 1920×1080 视口下应为 1，截图仍是设计像素，不要按缩小后的窗口去截。
4. **跑差异脚本**：对照图 vs 本轮截图 → 整体差异、分区差异、热力图。
5. **修复**：最差的 1～3 个分区，改**渲染规则**，不要瞎编补画面。然后从第 2 步再来。

**停止（满足任一即可）**：差异已经够小；或连续几轮几乎不动（收敛）；或到了当次轮次上限。常见写法是整体很小且分区都还行 / 连续 3 轮改善可忽略 / 满 25 轮——按当次任务调整，不必死守数字。

停下后：

- 写清最终差异、历史最优、还剩什么限制。**不要为了追 100% 继续空转。** 收敛后等人指出具体区域，再开下一轮针对性修。
- **必须出一张差异率曲线图**（和讲课 PPT 那种一样）：横轴轮数、纵轴差异率（`mismatch_pct`）。读每轮 `qa/round-*-report.json` 画折线，标出首轮、最优轮、明显下降的轮次。存到项目里，如 `qa/mismatch-curve.png`。可参考本 skill 的 `scripts/plot_mismatch.py`（Pillow，拷进项目即可）。

## 差异脚本怎么写

自己在项目里写可复跑脚本（Python + Pillow 足够），不要每轮只靠目测。要点：

- **输入**：项目内对照图 + 本轮截图；统一到同一画布尺寸、RGB。
- **整图**：`|ref - shot|`（`ImageChops.difference`）。
- **主指标**：失配像素占比（灰度差 > 阈值，可先用 30）比平均绝对差更有用。
- **分区**：按页面切几块（顶 / 左 / 中 / 右 / 底，坐标按当页定），每块算 mismatch，好知道先修哪。
- **输出**：热力图 + JSON/文本（整体、各区、排序）。每轮留档，便于看回归。

```python
diff = ImageChops.difference(ref, shot)
gray = diff.convert("L")
mismatch_pct = count(gray > threshold) / pixel_count * 100
# 每个 region box 同样算一遍
# 热力图：gray 着色后与 shot 混合
```

截图和 diff 可写在同一脚本或拆开，路径要稳定。循环结束后用各轮 JSON 画差异率曲线（见上）。

## 还原时注意（经验，不是清单）

- path 坐标经常不是局部 `0,0`，viewBox 按实际 bbox / 父坐标选，不要一律 `0 0 w h`。
- 白底 Mask 往往是裁切，不是白色填充。
- 环、甜甜圈实心了，再考虑 `evenodd` / `slice`。
- 渐变 NaN、`mix-blend-mode: screen` 容易洗画面；改完必跑差异，变差就回退。

更细的坑见 [lessons.md](lessons.md)；提示词范例见 [example-mastergo.md](example-mastergo.md)，按当次项目改。
