# 面试题库本地化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 从 GitHub `main` 的当前项目介绍与受跟踪文件中移除面试题库，同时在 Git 忽略的本地目录中无损保留完整题库。

**Architecture:** 先将受跟踪题库按原内容写入已被 `.git/info/exclude` 排除的 `local-only/`，以 SHA-256 证明副本无损；随后删除公开文件并清理两个当前导航入口。历史规格与计划保持不变，最终通过 Git 跟踪、忽略规则、文本检索和远端提交一致性完成验收。

**Tech Stack:** Markdown、Git、PowerShell、ripgrep、SHA-256

## Global Constraints

- `main` 最新版本不得包含 `interview/question-bank.md` 或当前文档中的题库入口。
- `local-only/interview/question-bank.md` 必须与删除前的公开题库字节一致。
- `local-only/` 必须继续仅由 `.git/info/exclude` 排除，不新增受跟踪的忽略配置。
- `local-only/interview/project-experience.md` 不得修改、覆盖或重命名。
- `docs/superpowers/specs/` 与 `docs/superpowers/plans/` 中的历史记录不得因本次清理而重写。
- 除题库入口外，完整项目介绍、设计流程、技术选型、技术取舍和算法说明保持不变。
- 提交差异不得包含密码、令牌、私钥或真实连接凭据。

---

## File Map

- Create locally only: `local-only/interview/question-bank.md` — 面试题库的完整本地副本，受 `.git/info/exclude` 排除。
- Delete publicly: `interview/question-bank.md` — 当前受 Git 跟踪的公开面试题库。
- Modify: `README.md` — 移除题库导航、目录行和实验流程中的面试追问要求。
- Modify: `docs/project-handbook/README.md` — 移除题库目录职责和相关文档入口。
- Preserve: `local-only/interview/project-experience.md` — 现有本地面试项目经验，不做任何修改。
- Preserve: `.git/info/exclude` — 已包含 `/local-only/`，不做任何修改。

### Task 1: 无损保存本地题库并清理公开内容

**Files:**
- Create locally only: `local-only/interview/question-bank.md`
- Delete: `interview/question-bank.md`
- Modify: `README.md:12,105,122`
- Modify: `docs/project-handbook/README.md:39,52`
- Preserve: `local-only/interview/project-experience.md`
- Preserve: `.git/info/exclude`

**Interfaces:**
- Consumes: `interview/question-bank.md` 当前完整内容；`.git/info/exclude` 中的 `/local-only/` 规则。
- Produces: 与源文件 SHA-256 一致的本地副本；不再公开题库的项目导航；删除公开题库的 Git 变更。

- [ ] **Step 1: 记录源题库、本地经验文件和工作区基线**

Run:

```powershell
Get-FileHash -Algorithm SHA256 -LiteralPath 'interview\question-bank.md'
Get-FileHash -Algorithm SHA256 -LiteralPath 'local-only\interview\project-experience.md'
git status --short --branch
```

Expected: 源题库哈希为 `BB0B7D90E4D8E0E9C48A97A5CE9BCB05F79EEFBC907338A24CAD129EDDB36FBC`；工作区无待提交修改。

- [ ] **Step 2: 用补丁创建逐字一致的本地副本**

Read the entire source:

```powershell
Get-Content -LiteralPath 'interview\question-bank.md' -Raw
```

Use `apply_patch` with `*** Add File: E:\test\work\java-roadmap\local-only\interview\question-bank.md` and use the complete command output as the file body without改写、摘要或占位符。

Verify immediately:

```powershell
Get-FileHash -Algorithm SHA256 -LiteralPath 'interview\question-bank.md'
Get-FileHash -Algorithm SHA256 -LiteralPath 'local-only\interview\question-bank.md'
git check-ignore -v 'local-only/interview/question-bank.md'
git ls-files --error-unmatch 'local-only/interview/question-bank.md'
git status --short
```

Expected: 两个 SHA-256 完全相同；`git check-ignore` 指向 `.git/info/exclude` 的 `/local-only/`；`git ls-files` 以非零状态报告文件未被跟踪；`git status` 不显示本地副本。

- [ ] **Step 3: 删除公开题库并精确更新根 README**

Use `apply_patch` to delete `interview/question-bank.md` and make these exact replacements in `README.md`:

```markdown
4. [学习日志](notes/learning-log.md) 与 [参考仓库阅读记录](references/README.md)：沉淀学习过程与延伸问题。
```

Delete this directory table row entirely:

```markdown
| [`interview/`](interview/question-bank.md) | 从实验提炼的面试追问与知识点。 |
```

Replace the experiment workflow sentence with:

```markdown
4. 补充 README、排障记录、学习日志和参考阅读记录。
```

- [ ] **Step 4: 精确更新项目手册**

Use `apply_patch` to replace the directory paragraph in `docs/project-handbook/README.md` with:

```markdown
`main` 是文档中心，根目录 [学习路线](../../README.md#学习路线)提供每个实验的独立分支入口。`labs/` 中的实验代码仍由独立分支维护，不并入 `main`；`notes/`、`references/` 与 `compose/` 分别保留学习过程、参考阅读和共享容器说明。本手册作为稳定入口，后续专题文档应从这里继续导航。
```

Delete this related-document entry entirely:

```markdown
- [面试题库](../../interview/question-bank.md)
```

- [ ] **Step 5: 验证公开删除、本地保留及历史边界**

Run:

```powershell
Test-Path -LiteralPath 'interview\question-bank.md'
$activeMatches = rg -n 'interview/question-bank|面试题库|`interview/`|面试追问' README.md docs/project-handbook/README.md
$historicalMatches = rg -n 'interview/question-bank|面试题库' docs/superpowers/specs docs/superpowers/plans
Test-Path -LiteralPath 'local-only\interview\question-bank.md'
git check-ignore -v 'local-only/interview/question-bank.md'
git status --short
git diff --check
```

Expected: 公开题库路径返回 `False`；`$activeMatches` 无输出；`$historicalMatches` 仍有历史记录；本地副本存在且命中排除规则；状态只显示 `README.md`、`docs/project-handbook/README.md` 和 `interview/question-bank.md` 的预期变更；`git diff --check` exit 0。

- [ ] **Step 6: 验证经验文件未变化并扫描敏感信息**

Run:

```powershell
Get-FileHash -Algorithm SHA256 -LiteralPath 'local-only\interview\project-experience.md'
git diff -- README.md docs/project-handbook/README.md interview/question-bank.md
git diff --name-only
git diff --unified=0 | rg '^\+[^+]' | rg -n '(?i)(password|passwd|secret|token|api[_-]?key|private[_-]?key|jdbc:[^ ]+://[^ ]+:[^ ]+@)'
```

Expected: `project-experience.md` 哈希与 Step 1 相同；差异仅为批准的文档清理；敏感信息扫描无新增凭据。若扫描命中普通说明文字，逐项人工确认其不是秘密值后才能继续。

- [ ] **Step 7: 提交公开仓库变更**

Run:

```powershell
git add -- README.md docs/project-handbook/README.md interview/question-bank.md
git diff --cached --check
git diff --cached --stat
git commit -m "docs: keep interview bank local only"
```

Expected: 提交仅包含两个 README 的修改和公开题库删除；`local-only/` 不在提交中。

### Task 2: 验证提交并同步 GitHub `main`

**Files:**
- Verify only: Git index, local `main`, remote `origin/main`

**Interfaces:**
- Consumes: Task 1 的公开文档提交和本地私有副本。
- Produces: 与本地 `main` 相同的 GitHub `origin/main`；最终验收证据。

- [ ] **Step 1: 对提交结果进行最终本地验收**

Run:

```powershell
git status --short --branch
git show --stat --oneline --decorate HEAD
git ls-tree -r --name-only HEAD | rg '^interview/question-bank\.md$'
rg -n 'interview/question-bank|面试题库|`interview/`|面试追问' README.md docs/project-handbook/README.md
Get-FileHash -Algorithm SHA256 -LiteralPath 'local-only\interview\question-bank.md'
git check-ignore -v 'local-only/interview/question-bank.md'
```

Expected: 工作树无受跟踪改动；提交统计只含预期文件；`HEAD` 树和当前公开文档中都没有题库；本地副本哈希仍为 `BB0B7D90E4D8E0E9C48A97A5CE9BCB05F79EEFBC907338A24CAD129EDDB36FBC` 且保持忽略。

- [ ] **Step 2: 推送 `main` 并验证远端一致性**

Run:

```powershell
git push origin main
git fetch origin main
git rev-parse main
git rev-parse origin/main
git status --short --branch
```

Expected: push 成功；`main` 与 `origin/main` 输出同一提交；状态为 `## main...origin/main`，且本地私有文件不出现。

- [ ] **Step 3: 检查 GitHub 最新树的公开结果**

Run:

```powershell
git ls-tree -r --name-only origin/main | rg '^interview/question-bank\.md$'
git show origin/main:README.md | rg -n 'interview/question-bank|面试题库|`interview/`|面试追问'
git show origin/main:docs/project-handbook/README.md | rg -n 'interview/question-bank|面试题库|`interview/`|面试追问'
```

Expected: 三条检索命令均无匹配，证明 GitHub 最新 `main` 不再公开题库及其当前入口。

## Completion Checklist

- [ ] 本地题库副本存在、哈希一致并被 Git 忽略。
- [ ] 本地项目经验文件哈希未变化。
- [ ] 公开题库文件已从 `main` 删除。
- [ ] 根 README 和项目手册已移除所有当前题库入口。
- [ ] 历史规格与计划仍保留变更记录。
- [ ] 暂存差异和最终提交均无敏感凭据。
- [ ] `main` 与 `origin/main` 提交一致。
