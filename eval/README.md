# zcode eval suite (v1)

固定 **20 道** Java 小任务，用来量 agent「改代码 + 通过 Check」的能力。  
结果可写进简历：**在自建 20 题集上通过率 X%，平均耗时 Ys**。

## 布局

```
eval/
  suite.json           # 题目录与评分约定
  bootstrap-tasks.ps1  # 生成/重置 tasks/*/fixture
  run.ps1              # 跑题：复制 fixture → zcode run → verify
  lib/Assert-JavaMain.ps1
  tasks/T01-.../
    task.json
    prompt.md          # 给 agent 的题面
    fixture/           # 有意写坏的小仓库
    verify.ps1         # 编译运行 Check.java，exit 0=过
  runs/<timestamp>/    # 运行产物（gitignore）
```

## 前置

1. Java 21+（`javac`/`java` 在 PATH）
2. 已 `powershell -File bin/install.ps1`
3. 配置 `ZCODE_LLM_API_KEY` / `ZCODE_LLM_BASE_URL`（或 `application-local.yml`）

首次或重置题目：

```powershell
powershell -File eval/bootstrap-tasks.ps1
```

## 跑法

```powershell
# 只检查：原始 fixture 应 FAIL（题目有效性）
powershell -File eval/run.ps1 -VerifyOnly

# 单题端到端
powershell -File eval/run.ps1 -Task T01

# 全量（建议先设迭代上限）
powershell -File eval/run.ps1 -MaxIterations 40
```

底层调用：

```text
bin\zcode.cmd run --workspace <workdir> --prompt-file <prompt.md>
```

`run` 为无头模式：强制 `auto-confirm`，不交互。

## 评分

| 字段 | 含义 |
|------|------|
| pass | agent 跑完后 `Check.java` 通过 |
| verify_failed | 改了但 Check 仍挂 |
| agent_error | `zcode run` 非 0 |
| precheck_already_pass | 题目坏了（未修前就过） |

`runs/*/summary.json` 含 `passRate` 与逐题结果。

## 题型分布（校招叙事）

| 类别 | 题号 | 考察 |
|------|------|------|
| bugfix | T01 T02 T04 T06 T08 T10 T11 T13 T14 T15 T17 T18 T20 | 读代码、定位、小改 |
| feature | T03 T05 T09 T12 T16 T19 | 按契约补实现 |
| refactor | T07 | 改调用点且行为正确 |

全部为 **纯 JDK、无 Maven**，verify 快、失败原因清晰。

## 简历写法示例

> 自研 Java coding agent（工具调用 / 权限 / 会话压缩 / checkpoint / MCP），并搭建 20 题 Java 评测集；在 Claude/xxx 上跑通率 **X%**，平均 **Ys/题**（详见 `eval/runs/.../summary.json`）。

## 下一步（可选增强）

- 基线：同题禁工具纯 chat，对比 passRate
- 记 `toolSteps`（从 `.events.jsonl` 统计 tool.end 次数）
- 加超时 `-TimeoutSec`
- 扩到 Spring 小模块题（需要 Maven，单独 `suite-spring.json`）
