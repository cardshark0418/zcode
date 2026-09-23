# 示例：MasterGo 模板1 原提示词

以下为用户在 `amastergo` 任务中给出的提示词（略去结尾「现在开始执行」亦可）。新任务时替换路径 / fileId / layer_id / 对照图说明即可。

---

# MasterGo 模板1：从零还原 + 自动截图对照循环

## 你的任务
在 `D:\aZhangChen\amastergo` 从零还原 MasterGo 设计「模板1」，并**无人值守循环**：实现 → 截图 → 与对照图 diff → 修复 → 再截图，直到达标或达上限。  
过程中**不要向我提问、不要等确认**，自己决策并继续。

## 设计源
- MasterGo：`https://mastergo.com/file/200885113048411`
- 图层：`模板1`，`layer_id=14:7081`
- 画布：`1920 × 1080`
- MCP：`@mastergo/magic-mcp`（环境变量 `MG_MCP_TOKEN`，`API_BASE_URL=https://mastergo.com`）

## 工程约定
- 工作目录：`D:\aZhangChen\amastergo`（已清空，你从零搭建）
- **对照图**（我已放入，你自行查找，常见名）：
  - `design-ref.png` / `ref.png` / `对照.png` / `design.png`
  - 或目录内唯一一张 `1920×1080` 量级的 PNG/JPG
- 找到后统一复制/重命名为：`design-data/design-ref.png`
- **禁止**从 `mastergo-template1`、`mastergo-template1-test` 拷代码当起点
- 可调用 MasterGo HTTP API：`/mcp/dsl`、`/mcp/design-sections` 等（Header：`X-MG-UserAccessToken`）

## 交付结构
```
amastergo/
  index.html
  assets/icons/          # 原生 SVG
  assets/images/         # 设计图片
  design-data/
    design-ref.png       # 对照图
    dsl_root.json        # 完整 DSL
  scripts/
    build_from_dsl.py    # 可重复构建
    screenshot_and_diff.py
  qa/
    round-NN-shot.png
    round-NN-diff.png
    round-NN-report.json
  README.md
```

形态：纯静态 HTML；固定 1920×1080；不要业务交互、不要响应式。

---

## 正确还原路线（必须）

**「设计原生资源导出 + 绝对定位拼装」**

1. 拉取完整 DSL：  
   `GET {API_BASE}/mcp/dsl?fileId=200885113048411&layerId=14:7081`  
   保存为 `design-data/dsl_root.json`

2. 导出资源：
   - 有 `path.data` → 独立 `.svg`，页面用 `<img>`
   - `fill` 中的 mastergo 图片 URL → 下载到 `assets/images/`
   - 无 path 的椭圆 → 用设计色值画椭圆，不要丢
   - TEXT → 原样输出，禁止编造文案/数字

3. **SVG viewBox（关键，以前翻过车）**  
   MasterGo 很多 `d` 是父级坐标，不是局部 `0,0`：
   - path 大致在 `0..w, 0..h` → `viewBox="0 0 w h"`
   - 否则若对齐 `relativeX/Y` → `viewBox="{rx} {ry} {w} {h}"`
   - 再否则 → path bbox 做 viewBox  
   容器仍按 DSL `relativeX/Y/width/height` 绝对定位。

4. 禁止：
   - 只靠 sections + `@@SVG` 占位（会丢大量装饰）
   - 统一错误 `viewBox="0 0 w h"` 导致大面积空白
   - 用手写 path / ECharts / 占位色块冒充设计
   - 向用户提问或中途停住等回复

---

## 自动对照循环（核心）

### 每轮（最多 25 轮）
1. 运行构建脚本，生成/更新 `index.html` 与资源  
2. 用 Playwright（或等价）打开本地页面，视口 **1920×1080**，截图到 `qa/round-{NN}-shot.png`  
   - 可用 `file:///D:/aZhangChen/amastergo/index.html`  
   - 等字体/图片加载完成再截（建议 networkidle + 额外 1–2s）  
3. 与 `design-data/design-ref.png` 做对比：
   - 尺寸统一到 1920×1080
   - 输出整图 diff 热力图：`qa/round-{NN}-diff.png`
   - 计算整体差异率（如平均绝对差 / 或失配像素占比）
   - **分区统计**（至少）：顶栏、左栏、中部、右栏、底部市场条  
4. 写 `qa/round-{NN}-report.json`：整体差异、各区差异、本轮改动摘要  
5. 选差异最大的 1～3 个区域修复（导出/viewBox/定位/图层顺序/缺失资源）  
6. 进入下一轮  

### 停止条件（满足任一即停）
- 整体差异 **< 2%**，且无任一分区 **> 5%**  
- 或连续 **3 轮** 整体差异改善 **< 0.3%**（收敛）  
- 或达到 **25 轮**  

停止后在 `qa/FINAL.md` 写：轮数、最终差异、仍可能存在的限制（字体未嵌入、混合模式等），**不要再问我**。

### Diff 实现建议
- Python + Pillow 即可：`|ref - shot|`，阈值出 mask，着色叠加  
- 报告里给出每区差异排序，优先修最差区  

---

## 质量底线
- 所有可见文字/数字来自 DSL  
- 图标与装饰来自设计 path 或官方图片 URL  
- 字体按 DSL family；缺失则记录，有字体文件则嵌入  
- 最终目视：顶栏、左三模块、中央球体/底座/悬浮信息、底部市场圆标、右侧列表与表格，无大块缺失、无明显错位  

## 开工顺序
1. 定位对照图 → 保存为对照文件  
2. 拉 DSL → 写 build 脚本 → 首版页面  
3. 写 screenshot_and_diff 脚本  
4. 进入自动循环直到停止条件  
5. 写 README + `qa/FINAL.md`  

现在开始执行，中途不要向用户提问。
