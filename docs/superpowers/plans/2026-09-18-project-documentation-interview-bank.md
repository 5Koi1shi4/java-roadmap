# Project Documentation and Interview Bank Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish a complete project handbook and a 90-question evidence-backed bank for experiments one through nine, mark experiment nine as full-stack, and generate a strictly local interview-experience document that never enters Git history.

**Architecture:** Public documentation is split by responsibility: the root README is the navigation surface, the interview bank contains only questions and answers, and four handbook files explain the project, design process, technology stack, algorithms, and trade-offs. The local interview narrative lives under `local-only/` and is excluded only through `.git/info/exclude`, keeping personal preparation separate from public project history.

**Tech Stack:** Markdown, Git branches as experiment evidence sources, PowerShell verification, ripgrep, GitHub `main`.

## Global Constraints

- Work on `main`; experiment implementations remain on their independent branches and are not merged into `main`.
- Public claims must be supported by tracked code, SQL, configuration, tests, or acceptance records.
- Describe planned or optional capabilities explicitly as future evolution, never as implemented behavior.
- Use placeholders for unknown company, role, date, school, or personal information in the local-only document.
- Do not include real credentials, tokens, private keys, local absolute paths, or personal contact details.
- `interview/question-bank.md` contains only the AI/reference disclaimer, experiment headings, questions, reference answers, evidence, and focused follow-ups.
- The public interview bank contains exactly 90 continuously numbered questions across experiments one through nine.
- `local-only/interview/project-experience.md` must be ignored by `.git/info/exclude`, remain untracked, and never be staged or pushed.
- Public changes are pushed only after fetching `origin/main`, proving a fast-forward update, and passing all documentation checks.

---

### Task 1: Mark experiment nine as full-stack and create the handbook entry point

**Files:**
- Modify: `README.md`
- Create: `docs/project-handbook/README.md`

**Interfaces:**
- Consumes: experiment summaries already in `README.md`; design and acceptance records under `docs/superpowers/`; published branch `learning/ai-campus-support`.
- Produces: stable public navigation links used by every later handbook document.

- [ ] **Step 1: Update the root navigation**

Use `apply_patch` to change the experiment-nine title/status text so it explicitly contains `全栈实验`. Add a `项目手册` entry under `目录导航` linking to `docs/project-handbook/README.md`. Preserve the independent-branch link and accepted commit `9edcaed`.

- [ ] **Step 2: Write the project handbook overview**

Create `docs/project-handbook/README.md` with these exact sections:

```markdown
# Java Backend Roadmap 项目手册
## 项目定位与使用边界
## 九个实验的递进路线
## 总体能力地图
## 全链路数据流
## 安全、可靠性与数据所有权原则
## 分支与目录模型
## 验证证据
## 手册导航
```

The experiment route must cover JWT/RBAC, Redis caching, seckill/idempotency, RabbitMQ reliability, Elasticsearch search, secure file storage, the campus marketplace, Spring Cloud extraction, and the full-stack AI support journey. State that this is a reproducible learning project rather than a production deployment.

- [ ] **Step 3: Verify links and wording**

Run:

```powershell
rg -n '实验九.*全栈|全栈实验|docs/project-handbook/README.md' README.md
Test-Path 'docs/project-handbook/README.md'
rg -n '^## ' docs/project-handbook/README.md
git diff --check
```

Expected: the README contains the full-stack label and handbook link, the handbook exists with all eight sections, and `git diff --check` prints nothing.

- [ ] **Step 4: Commit the navigation and overview**

```powershell
git add -- README.md docs/project-handbook/README.md
git commit -m "docs: add full-stack project handbook overview"
```

### Task 2: Document the complete design process

**Files:**
- Create: `docs/project-handbook/design-process.md`

**Interfaces:**
- Consumes: `docs/project-handbook/README.md`; all design specs and plans under `docs/superpowers/`; experiment README files from independent branches.
- Produces: the canonical explanation of how requirements become boundaries, invariants, failure semantics, and acceptance evidence.

- [ ] **Step 1: Read the authoritative design sources**

Run:

```powershell
rg -n '^#|^## ' docs/superpowers/specs docs/superpowers/plans
git show learning/campus-market:labs/07-campus-market/README.md | Select-String -Pattern '^#|^## '
git show learning/spring-cloud-split:labs/08-spring-cloud-split/README.md | Select-String -Pattern '^#|^## '
git show learning/ai-campus-support:labs/09-ai-campus-support/README.md | Select-String -Pattern '^#|^## '
```

Expected: sources cover the monolith, service extraction, product projection, and AI full-stack journey.

- [ ] **Step 2: Write the design-process document**

Create the file with these exact sections:

```markdown
# 设计流程与实验演进
## 1. 从业务目标识别风险
## 2. 划分领域、服务与数据所有权
## 3. 区分事实源、缓存与派生索引
## 4. 建立状态机和并发不变量
## 5. 设计外部依赖与失败语义
## 6. 安全、隐私与最小披露
## 7. 可观测性与人工恢复入口
## 8. 测试金字塔与真实依赖验收
## 9. 实验一至九的演进过程
## 10. 从实验方案走向生产仍缺什么
```

For each phase, explain the decision inputs, repository example, failure prevented, and validation evidence. The final section must identify production gaps such as capacity planning, multi-region recovery, key management, compliance approval, real provider contracts, and sustained operations.

- [ ] **Step 3: Verify evidence language**

Run:

```powershell
rg -n '事实源|不变量|失败语义|最小披露|Testcontainers|Playwright|生产' docs/project-handbook/design-process.md
rg -n '已经生产|线上用户|生产流量|真实支付上线' docs/project-handbook/design-process.md
git diff --check
```

Expected: the first command finds every required concept; the second prints nothing; the whitespace check prints nothing.

- [ ] **Step 4: Commit the design process**

```powershell
git add -- docs/project-handbook/design-process.md
git commit -m "docs: explain project design process and evolution"
```

### Task 3: Explain technology selection with advantages and disadvantages

**Files:**
- Create: `docs/project-handbook/technology-stack.md`

**Interfaces:**
- Consumes: root README technical baseline; Maven POMs, package manifests, Compose files, and experiment READMEs from the independent branches.
- Produces: an evidence-based technology catalogue linked from the handbook index.

- [ ] **Step 1: Inventory versions and responsibilities from tracked files**

Use these commands; do not infer a single shared framework version when branches differ:

```powershell
git show learning/security-rbac:labs/01-security-rbac/pom.xml | Select-String -Pattern 'java.version|spring-boot'
git show learning/redis-cache:labs/02-redis-cache/pom.xml | Select-String -Pattern 'java.version|spring-boot'
git show learning/spring-cloud-split:labs/08-spring-cloud-split/pom.xml | Select-String -Pattern 'java.version|spring-boot|spring-cloud'
git show learning/ai-campus-support:labs/09-ai-campus-support/pom.xml | Select-String -Pattern 'java.version|spring-boot|spring-cloud|spring-ai'
git show learning/ai-campus-support:labs/09-ai-campus-support/support-web/package.json
```

- [ ] **Step 2: Write the technology-stack document**

Create these exact sections:

```markdown
# 技术栈、选型依据与优缺点
## 1. Java、Maven 与 Spring Boot
## 2. Spring Security、JWT、RS256 与 JWKS
## 3. MySQL、JDBC 与 Flyway
## 4. Redis、Redisson 与 Lua
## 5. RabbitMQ 与 Spring AMQP
## 6. Elasticsearch 与 SmartCN
## 7. MinIO 与对象存储
## 8. Spring Cloud、Eureka 与 Gateway
## 9. Spring AI、RAG 与模型适配器
## 10. React、TypeScript、Vite 与 Nginx
## 11. Docker Compose、Testcontainers、JUnit、Vitest 与 Playwright
## 12. Prometheus、Grafana、Micrometer 与 k6
## 13. 选型总结与适用边界
```

Every technology subsection must contain `项目职责`, `选择理由`, `优点`, `缺点`, `替代方案`, and `边界`. Explain version differences between experiment branches where verified.

- [ ] **Step 3: Verify structural completeness**

Run:

```powershell
$text = Get-Content -Raw 'docs/project-handbook/technology-stack.md'
foreach ($label in '项目职责','选择理由','优点','缺点','替代方案','边界') {
  if (($text.Split($label).Count - 1) -lt 12) { throw "缺少足够的 $label 说明" }
}
rg -n '^## ' docs/project-handbook/technology-stack.md
git diff --check
```

Expected: all thirteen sections exist, every technology group covers all six decision dimensions, and the whitespace check prints nothing.

- [ ] **Step 4: Commit the technology guide**

```powershell
git add -- docs/project-handbook/technology-stack.md
git commit -m "docs: explain technology choices and tradeoffs"
```

### Task 4: Explain algorithms, concurrency controls, and reliability mechanisms

**Files:**
- Create: `docs/project-handbook/algorithms-and-tradeoffs.md`

**Interfaces:**
- Consumes: implementation and tests on experiment branches, especially experiments two through nine.
- Produces: the canonical deep-dive for algorithm and mechanism interview preparation.

- [ ] **Step 1: Confirm implementation evidence**

Search branch implementations before writing:

```powershell
git grep -n -E 'afterCommit|RLock|Lua|INCR|EXPIRE' learning/redis-cache -- 'labs/02-redis-cache/**'
git grep -n -E 'Idempotency|stock = stock - 1|FOR UPDATE' learning/seckill-inventory -- 'labs/03-seckill-inventory/**'
git grep -n -E 'claimToken|lease|Outbox|Inbox|external_gte|tombstone' learning/ai-campus-support -- 'labs/09-ai-campus-support/**'
git grep -n -E 'SupportCursor|SHA-256|reference_count|watermark|PolicyRetriever' learning/ai-campus-support -- 'labs/09-ai-campus-support/**'
```

- [ ] **Step 2: Write the algorithms document**

Create sections for exactly these mechanisms:

```markdown
# 算法、并发控制与可靠性机制
## 1. BCrypt 密码哈希与 SHA-256 高熵令牌摘要
## 2. Cache Aside、提交后失效与空值缓存
## 3. 热点 Key 重建锁与二次检查
## 4. Redis Lua 固定时间桶限流
## 5. 条件更新防超卖与乐观并发
## 6. 幂等键、请求摘要与原响应重放
## 7. 有限状态机与截止时间竞争
## 8. Transactional Outbox、Inbox 与至少一次投递
## 9. 重试分类、退避、TTL 与 DLX
## 10. 租约、owner token、claim token 与 fencing
## 11. Elasticsearch external version、tombstone 与稳定排序
## 12. 在线索引重建、高水位补放与原子别名切换
## 13. 内容哈希、引用计数与对象清理
## 14. 对象级 ACL、同构 404 与短时授权 URL
## 15. 游标分页与有界查询
## 16. RAG 检索、来源约束与结构化事实隔离
## 17. 机制组合时的整体取舍
```

Each mechanism must include: problem, repository example, core steps or pseudocode, correctness invariant, runtime/storage cost, advantages, disadvantages, and common misuse. Do not call fixed-window limiting a sliding window or token bucket.

- [ ] **Step 3: Verify every mechanism has trade-off language**

Run:

```powershell
$text = Get-Content -Raw 'docs/project-handbook/algorithms-and-tradeoffs.md'
foreach ($label in '解决问题','核心步骤','正确性不变量','运行代价','优点','缺点','常见误用') {
  if (($text.Split($label).Count - 1) -lt 16) { throw "算法文档缺少 $label" }
}
rg -n '滑动窗口|令牌桶' docs/project-handbook/algorithms-and-tradeoffs.md
git diff --check
```

Expected: all coverage checks pass; the forbidden-algorithm search prints nothing; the whitespace check prints nothing.

- [ ] **Step 4: Commit the mechanism guide**

```powershell
git add -- docs/project-handbook/algorithms-and-tradeoffs.md
git commit -m "docs: explain algorithms and reliability mechanisms"
```

### Task 5: Rebuild the interview bank as a pure 90-question resource

**Files:**
- Modify: `interview/question-bank.md`

**Interfaces:**
- Consumes: existing main bank; all experiment README files; experiment-eight and experiment-nine branch question banks; implementation and test names on each branch.
- Produces: the sole public interview-question resource, continuously numbered 1 through 90.

- [ ] **Step 1: Read all source question material**

Run:

```powershell
Get-Content -Raw 'interview/question-bank.md'
git show learning/spring-cloud-split:labs/08-spring-cloud-split/interview/question-bank.md
git show learning/ai-campus-support:labs/09-ai-campus-support/interview/question-bank.md
foreach ($item in @(
  'learning/security-rbac:labs/01-security-rbac/README.md',
  'learning/redis-cache:labs/02-redis-cache/README.md',
  'learning/seckill-inventory:labs/03-seckill-inventory/README.md',
  'feat/order-mq-reliable-messaging:labs/04-order-mq/README.md',
  'learning/elasticsearch-search:labs/05-elasticsearch-search/README.md',
  'learning/secure-file-service:labs/06-file-service/README.md',
  'learning/campus-market:labs/07-campus-market/README.md',
  'learning/spring-cloud-split:labs/08-spring-cloud-split/README.md',
  'learning/ai-campus-support:labs/09-ai-campus-support/README.md'
)) { git show $item | Out-Null; if ($LASTEXITCODE -ne 0) { throw "无法读取 $item" } }
```

Expected: every source is readable without switching branches.

- [ ] **Step 2: Replace the bank with the required pure structure**

Use `apply_patch` to rebuild the file. The opening must state that AI generated the questions from repository implementation and tests, that they are for reference only, and that readers must verify against actual code. Then create these nine experiment sections and topic sets:

- Experiment 1, questions 1–10: request authentication, 401/403, access/refresh separation, refresh hashing, concurrent rotation, logout limits, BCrypt, configured JWT keys, Flyway, MockMvc versus real HTTP/MySQL.
- Experiment 2, questions 11–18: Cache Aside, submit-after-commit eviction, rollback behavior, empty-value cache, hotspot rebuild locks, second check and lock timeout, Redis failure semantics, metrics and k6 evidence.
- Experiment 3, questions 19–26: conditional stock decrement, transaction atomicity, user/product unique constraint, idempotency-key plus digest, exact response replay, cross-instance ownership/takeover, lock order and deadlocks, real MySQL concurrency proof.
- Experiment 4, questions 27–36: order state conditions, Outbox, leases, consumer transaction/ACK, duplicate delivery, failure classification, bounded retry, TTL buckets/DLX, protocol validation, shared Testcontainers isolation.
- Experiment 5, questions 37–46: MySQL fact source, search Outbox, dispatcher lease, claim-token fencing, external version, tombstone, SmartCN, relevance/filter composition, atomic alias cutover, watermark recovery.
- Experiment 6, questions 47–54: split upload transactions, privacy-safe deduplication, homogeneous 404, content versus presigned URL, leases/tokens/generation, idempotent physical deletion, observability privacy, repeated MinIO recovery.
- Experiment 7, questions 55–66: email verification boundary, batch inventory, idempotency result, unknown payment, partial refund reservation, deadline race, Outbox/Inbox/confirm/fencing, trusted return proof, quarantine stock, post-settlement warranty, evidence ACL, three-round recovery.
- Experiment 8, questions 67–80: identity-first extraction, gateway revalidation, RS256/JWKS, hot/cold key-cache failure, liveness/readiness, no automatic write retry, database ownership, deadlock lock order, classpath/autoconfiguration isolation, Docker ignore, product projection fact ownership, Inbox plus aggregate version, online rebuild fencing, zero-event replay barrier.
- Experiment 9, questions 81–90: private prompt/token minimization, read-only AI, authorization versus hallucination, three dependency failure semantics, in-memory browser token, versioned policy citations, prompt-injection boundary, Redis fixed-window rate limiting and fail-closed behavior, structured facts versus generated text, desktop/mobile full-stack E2E evidence.

Every question must contain `**参考回答：**` and `**代码/测试证据：**`; add `**追问：**` only when it clarifies an important boundary. Delete the old `技术取舍速记` table and every non-question appendix.

- [ ] **Step 3: Verify question purity, numbering, and coverage**

Run:

```powershell
$headings = rg --pcre2 -o '^### \K\d+(?=\.)' interview/question-bank.md | ForEach-Object { [int]$_ }
if ($headings.Count -ne 90) { throw "题目数不是 90：$($headings.Count)" }
if (Compare-Object (1..90) $headings) { throw '题号不是连续的 1..90' }
$experiments = rg -n '^## 实验[一二三四五六七八九]' interview/question-bank.md
if (($experiments | Measure-Object).Count -ne 9) { throw '实验章节不是 9 个' }
$answers = (rg -n '^\*\*参考回答：\*\*' interview/question-bank.md | Measure-Object).Count
$evidence = (rg -n '^\*\*代码/测试证据：\*\*' interview/question-bank.md | Measure-Object).Count
if ($answers -ne 90 -or $evidence -ne 90) { throw "回答=$answers，证据=$evidence" }
if (rg -n '^## (技术取舍|项目介绍|学习路线|复习计划)' interview/question-bank.md) { throw '题库仍包含非题库章节' }
rg -n 'AI.*生成|仅供.*参考|结合.*代码.*验证' interview/question-bank.md
git diff --check
```

Expected: 90 continuous questions, nine experiment sections, 90 answers, 90 evidence blocks, the AI/reference statement present, and no forbidden sections or whitespace errors.

- [ ] **Step 4: Commit the interview bank**

```powershell
git add -- interview/question-bank.md
git commit -m "docs: complete interview bank for all experiments"
```

### Task 6: Generate the local-only project and internship narrative

**Files:**
- Modify locally only: `.git/info/exclude`
- Create locally only: `local-only/interview/project-experience.md`

**Interfaces:**
- Consumes: public handbook, question bank, repository acceptance counts, and verified experiment outcomes.
- Produces: a private preparation document that Git ignores and public docs never reference.

- [ ] **Step 1: Add the local exclusion before creating the file**

Use `apply_patch` on `.git/info/exclude` to add exactly:

```gitignore
/local-only/
```

Run:

```powershell
git check-ignore -v local-only/test-placeholder.md
```

Expected: `.git/info/exclude` is reported as the matching source.

- [ ] **Step 2: Create the local interview document**

Use `apply_patch` to create `local-only/interview/project-experience.md` with these sections:

```markdown
# 面试项目经验与实习经验准备
## 使用边界
## 30 秒项目介绍
## 2 分钟项目介绍
## 简历项目经历写法
## 实习经历写法
## STAR 案例
## 可验证的数据与成果
## 高频追问
## 不能夸大的边界
```

Use the literal safe placeholders `[公司名称]`, `[岗位名称]`, `[起止时间]`, and `[学校或团队]` for unknown personal facts. Use only repository-verifiable metrics such as nine experiments, accepted test counts recorded in tracked acceptance docs, the experiment-nine 639 backend tests, 13 Vitest tests, and 10 Playwright journeys. Do not invent production QPS, revenue, team size, user count, or incident impact.

- [ ] **Step 3: Prove the file is absent from Git state**

Run:

```powershell
git check-ignore -v local-only/interview/project-experience.md
git status --porcelain --untracked-files=all
git ls-files --error-unmatch local-only/interview/project-experience.md
```

Expected: the first command points to `.git/info/exclude`; status does not list `local-only/`; `git ls-files` exits nonzero because the file is untracked. Do not stage or commit this task.

### Task 7: Cross-link, validate, and publish the public documentation

**Files:**
- Modify if needed: `docs/project-handbook/README.md`
- Verify only: `README.md`, `interview/question-bank.md`, `docs/project-handbook/*.md`

**Interfaces:**
- Consumes: all public outputs from Tasks 1–5 and the isolation proof from Task 6.
- Produces: a clean fast-forward update of GitHub `main`, while the local-only narrative remains absent from Git.

- [ ] **Step 1: Add final handbook cross-links**

Ensure `docs/project-handbook/README.md` links to `design-process.md`, `technology-stack.md`, `algorithms-and-tradeoffs.md`, the root README, and `../../interview/question-bank.md`. Use `apply_patch` for any correction.

- [ ] **Step 2: Verify every expected public file and link target**

Run:

```powershell
$paths = @(
  'README.md',
  'interview/question-bank.md',
  'docs/project-handbook/README.md',
  'docs/project-handbook/design-process.md',
  'docs/project-handbook/technology-stack.md',
  'docs/project-handbook/algorithms-and-tradeoffs.md'
)
foreach ($path in $paths) { if (-not (Test-Path -LiteralPath $path)) { throw "缺少 $path" } }
rg -n 'design-process.md|technology-stack.md|algorithms-and-tradeoffs.md|interview/question-bank.md' docs/project-handbook/README.md
git diff --check
```

Expected: all files and links exist; the whitespace check prints nothing.

- [ ] **Step 3: Scan public changes for secrets and local paths**

Run:

```powershell
$patterns = '-----BEGIN (RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----|gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,}|sk-[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{30,}|Bearer [A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+|[A-Z]:\\Users\\'
$files = git diff --name-only origin/main..main
$hits = foreach ($file in $files) {
  if (Test-Path -LiteralPath $file -PathType Leaf) {
    Select-String -LiteralPath $file -Pattern $patterns -AllMatches -ErrorAction SilentlyContinue
  }
}
if ($hits) { $hits | ForEach-Object { "$($_.Path):$($_.LineNumber)" }; throw '公开变更包含疑似敏感信息或本机路径' }
```

Expected: no hits.

- [ ] **Step 4: Confirm local-only isolation and clean public worktree**

Run:

```powershell
git check-ignore -v local-only/interview/project-experience.md
$status = git status --porcelain --untracked-files=all
if ($status) { $status; throw '公开工作树仍有未提交变更' }
```

Expected: local exclusion is shown and the public worktree is clean.

- [ ] **Step 5: Commit any final cross-link correction**

If Step 1 changed the handbook index:

```powershell
git add -- docs/project-handbook/README.md
git commit -m "docs: finalize project handbook navigation"
```

If Step 1 made no change, do not create an empty commit.

- [ ] **Step 6: Fetch and prove the update is fast-forward**

Run:

```powershell
git fetch origin main
git status --short --branch
git log --oneline --left-right origin/main...main
git merge-base --is-ancestor origin/main main
```

Expected: `main` is only ahead, no `<` commits appear, and `merge-base --is-ancestor` exits 0.

- [ ] **Step 7: Push and verify GitHub `main`**

Run:

```powershell
git push origin main
$local = git rev-parse main
$remote = (git ls-remote --heads origin main).Split("`t")[0]
if ($local -ne $remote) { throw "远端 main 未同步：local=$local remote=$remote" }
git status --short --branch
```

Expected: push succeeds, local and remote hashes match, and status is `## main...origin/main` with no changes.
