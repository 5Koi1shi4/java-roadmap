# Isolated Experiment Branches Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert the repository into a documentation-only `main` branch plus four history-preserving branches whose active trees each contain exactly one experiment.

**Architecture:** Rewrite each experiment branch in an independent local clone with a path-only index filter, verify the rewritten subtree is byte-identical to its canonical source, then import the candidate commits into the primary repository. Preserve all dirty and untracked user files by detaching legacy worktrees, create new isolated worktrees for verification, and publish `main` plus all experiment refs in one atomic push protected by exact `--force-with-lease` values.

**Tech Stack:** Git 2.39.1 for Windows, PowerShell, Git `filter-branch`/plumbing commands, Maven Wrapper, JDK 17, Docker/Testcontainers, GitHub pull-request API.

## Global Constraints

- `main` is the only documentation center and must not track `labs/`.
- Each experiment branch may track only root `.gitignore` plus its one `labs/<experiment>/` directory.
- `AGENTS.md` must remain on the local filesystem, must not be tracked by any active branch, and must be ignored by `/AGENTS.md`.
- Preserve the uncommitted `labs/02-redis-cache/.env.example` modification byte-for-byte; record its SHA-256 before and after migration.
- Preserve all untracked files in the existing Redis and seckill worktrees; do not clean, reset, move, or delete those paths.
- Existing branch history is rewritten only with `--force-with-lease`; never use unqualified `--force`.
- All remote ref updates must be sent in one `git push --atomic` operation after local verification.
- Before rewriting, create local backup refs for every original head; do not push or delete the backups during this task.
- Close PR #2 only after the atomic ref update succeeds.
- All subagents use `gpt-5.6-luna` with reasoning effort `high`; every implementation task receives specification and quality review.
- Do not expose secret values in command output or reports.

---

### Task 1: Capture Preconditions and Recovery References

**Files:**
- Inspect: `<workspace>/java-roadmap/.git/` through Git commands only
- Preserve: `<workspace>/java-roadmap/labs/02-redis-cache/.env.example`
- Preserve: `<workspace>/java-roadmap/.worktrees/redis-cache/`
- Preserve: `<workspace>/java-roadmap/.worktrees/seckill-inventory/`
- Preserve: `<workspace>/java-roadmap/.worktrees/order-mq-reliable-messaging/`

**Interfaces:**
- Consumes: expected refs `origin/main=04e131dabc41e205e9fb8ade50139ba895e4d558`, `learning/redis-cache=e58183befbc54c7e3cf686f526e0a11afd11c0d2`, `learning/seckill-inventory=b1b7ffc25d67d0923ff47e390e5517f7aa3ff406`, `feat/order-mq-reliable-messaging=2ab998bd92f23295b36f6abffede674b8878dfa9`.
- Produces: local `backup/2026-08-23/*-before-isolation` refs and recorded SHA-256 for the dirty `.env.example`.

- [ ] **Step 1: Verify exact local and tracked remote heads**

Run from `<workspace>\java-roadmap`:

```powershell
git -c safe.directory='<workspace>/java-roadmap' rev-parse origin/main
git -c safe.directory='<workspace>/java-roadmap' rev-parse main
git -c safe.directory='<workspace>/java-roadmap' rev-parse learning/redis-cache
git -c safe.directory='<workspace>/java-roadmap' rev-parse learning/seckill-inventory
git -c safe.directory='<workspace>/java-roadmap' rev-parse feat/order-mq-reliable-messaging
git -c safe.directory='<workspace>/java-roadmap' status --short --branch
git -c safe.directory='<workspace>/java-roadmap' show-ref --verify --quiet refs/heads/rewrite/security-rbac
if ($LASTEXITCODE -eq 0) { throw 'Unexpected pre-existing rewrite ref' }
if (Test-Path -LiteralPath '<workspace>\java-roadmap-branch-rewrite') { throw 'Unexpected pre-existing rewrite root' }
git -c safe.directory='<workspace>/java-roadmap/.worktrees/redis-cache' -C .worktrees/redis-cache status --short --branch
git -c safe.directory='<workspace>/java-roadmap/.worktrees/seckill-inventory' -C .worktrees/seckill-inventory status --short --branch
git -c safe.directory='<workspace>/java-roadmap/.worktrees/order-mq-reliable-messaging' -C .worktrees/order-mq-reliable-messaging status --short --branch
```

Expected: the four published heads equal the values in **Interfaces**; `main` contains only the known modified `.env.example`; Redis and seckill contain only their already-recorded untracked files; order is clean. Stop if any tracked remote head differs.

- [ ] **Step 2: Record the dirty file hash without printing content**

```powershell
Get-FileHash -Algorithm SHA256 -LiteralPath 'labs/02-redis-cache/.env.example' | Select-Object Algorithm, Hash, Path
```

Expected: one SHA-256 value. Store the hash in the task report without file content.

- [ ] **Step 3: Create local recovery refs**

```powershell
git -c safe.directory='<workspace>/java-roadmap' branch backup/2026-08-23/main-before-isolation main
git -c safe.directory='<workspace>/java-roadmap' branch backup/2026-08-23/redis-before-isolation learning/redis-cache
git -c safe.directory='<workspace>/java-roadmap' branch backup/2026-08-23/seckill-before-isolation learning/seckill-inventory
git -c safe.directory='<workspace>/java-roadmap' branch backup/2026-08-23/order-before-isolation feat/order-mq-reliable-messaging
git -c safe.directory='<workspace>/java-roadmap' show-ref --heads | Select-String 'backup/2026-08-23/'
```

Expected: four local backup refs point to the original heads. Do not configure upstreams and do not push these refs.

- [ ] **Step 4: Commit nothing**

This task changes refs only. Reviewer confirms that no working-tree file or remote ref changed.

---

### Task 2: Remove `AGENTS.md` from Unpublished `main` History

**Files:**
- Preserve locally: `AGENTS.md`
- Preserve locally: `labs/02-redis-cache/.env.example`
- Rewrite locally: commits after `1ada79b`

**Interfaces:**
- Consumes: backup refs from Task 1 and the clean audit result for blob `f2a0fce1`.
- Produces: `main` containing the order design, plan, and isolated-branch spec/plan but no reachable `AGENTS.md` commit between `origin/main` and `main`; local `AGENTS.md` remains present and untracked.

- [ ] **Step 1: Stash only the known modified experiment file**

```powershell
git -c safe.directory='<workspace>/java-roadmap' stash push -m 'preserve redis env example before branch isolation' -- labs/02-redis-cache/.env.example
git -c safe.directory='<workspace>/java-roadmap' status --short
```

Expected: main worktree is clean and the stash contains only `labs/02-redis-cache/.env.example`.

- [ ] **Step 2: Drop the unpublished `AGENTS.md` commit while replaying later documentation commits**

```powershell
git -c safe.directory='<workspace>/java-roadmap' rebase --onto 1ada79b 594a0af main
git -c safe.directory='<workspace>/java-roadmap' log --oneline --decorate origin/main..main
git -c safe.directory='<workspace>/java-roadmap' log --format='%H' origin/main..main -- AGENTS.md
```

Expected: order design/plan plus the isolated-branch spec and this plan remain; the final command prints nothing.

- [ ] **Step 3: Restore `AGENTS.md` as an untracked local file without shell redirection**

```powershell
git -c safe.directory='<workspace>/java-roadmap' checkout backup/2026-08-23/main-before-isolation -- AGENTS.md
git -c safe.directory='<workspace>/java-roadmap' reset HEAD -- AGENTS.md
Test-Path -LiteralPath 'AGENTS.md'
git -c safe.directory='<workspace>/java-roadmap' status --short -- AGENTS.md
```

Expected: `Test-Path` is `True`; status reports `?? AGENTS.md` until the ignore rule is added; the file is not staged.

- [ ] **Step 4: Restore the user's dirty file**

```powershell
git -c safe.directory='<workspace>/java-roadmap' stash pop
git -c safe.directory='<workspace>/java-roadmap' status --short
```

Expected: `labs/02-redis-cache/.env.example` is modified exactly as before and `AGENTS.md` is untracked. If stash application conflicts, stop and restore from the Task 1 backup ref; do not resolve by discarding either side.

- [ ] **Step 5: Verify the dirty file hash**

```powershell
Get-FileHash -Algorithm SHA256 -LiteralPath 'labs/02-redis-cache/.env.example' | Select-Object Algorithm, Hash, Path
```

Expected: the hash exactly matches Task 1.

---

### Task 3: Convert `main` into the Documentation Center

**Files:**
- Modify: `.gitignore`
- Modify: `README.md`
- Modify from experiment-four canonical versions: `notes/learning-log.md`
- Modify from experiment-four canonical versions: `interview/question-bank.md`
- Modify from experiment-four canonical versions: `references/README.md`
- Remove from Git index only: `labs/`
- Preserve locally: `AGENTS.md`, `labs/`

**Interfaces:**
- Consumes: clean rewritten local history from Task 2 and experiment-four documentation at `backup/2026-08-23/order-before-isolation`.
- Produces: documentation-only `main` head with cross-branch links and local-only ignored experiment files.

- [ ] **Step 1: Bring accepted experiment-four center documents onto `main`**

```powershell
git -c safe.directory='<workspace>/java-roadmap' checkout backup/2026-08-23/order-before-isolation -- interview/question-bank.md notes/learning-log.md references/README.md
git -c safe.directory='<workspace>/java-roadmap' diff --cached --name-status
```

Expected: only the three center-document paths are staged.

- [ ] **Step 2: Add permanent local-only ignore rules**

Use `apply_patch` to add these exact root rules to `.gitignore` without removing existing rules:

```gitignore
# Local agent instructions are never committed.
/AGENTS.md

# Experiment projects remain in local main worktree but live in isolated branches.
/labs/
```

Expected: `git check-ignore -v AGENTS.md labs/02-redis-cache/.env.example` identifies the new root rules after the paths leave the index.

- [ ] **Step 3: Replace relative experiment links in root README**

Keep the roadmap, status definitions, technology baseline, learning loop, and documentation-center navigation. Replace the experiment table and accepted-experiment entry links with these exact destinations:

```markdown
| 阶段 | 项目 | 状态 | 独立分支入口 |
|---|---|---|---|
| 1 | JWT 与 RBAC 权限服务 | 已验收 | [learning/security-rbac](https://github.com/5Koi1shi4/java-roadmap/tree/learning/security-rbac/labs/01-security-rbac) |
| 2 | Redis 缓存与一致性 | 已验收 | [learning/redis-cache](https://github.com/5Koi1shi4/java-roadmap/tree/learning/redis-cache/labs/02-redis-cache) |
| 3 | 秒杀、库存与接口幂等 | 已验收 | [learning/seckill-inventory](https://github.com/5Koi1shi4/java-roadmap/tree/learning/seckill-inventory/labs/03-seckill-inventory) |
| 4 | 订单状态机与可靠消息 | 已验收 | [feat/order-mq-reliable-messaging](https://github.com/5Koi1shi4/java-roadmap/tree/feat/order-mq-reliable-messaging/labs/04-order-mq) |
```

Add an explicit repository rule: `main` stores documentation only; experiment branches are not merged into `main`; experiment acceptance updates are separate documentation commits.

- [ ] **Step 4: Remove every experiment from the `main` index while preserving working files**

First verify the resolved target:

```powershell
(Resolve-Path -LiteralPath 'labs').Path
git -c safe.directory='<workspace>/java-roadmap' ls-files labs | Measure-Object
```

Expected: resolved path is exactly `<workspace>\java-roadmap\labs`; the tracked-file count is nonzero.

Then remove only from the index:

```powershell
git -c safe.directory='<workspace>/java-roadmap' rm -r --cached -f -- labs
Test-Path -LiteralPath 'labs/01-security-rbac/README.md'
Test-Path -LiteralPath 'labs/02-redis-cache/.env.example'
Test-Path -LiteralPath 'labs/03-seckill-inventory/README.md'
```

Expected: all three `Test-Path` calls are `True`; Git stages deletions but does not delete local project files.

- [ ] **Step 5: Verify local-only protection and user data preservation**

```powershell
git -c safe.directory='<workspace>/java-roadmap' check-ignore -v AGENTS.md labs/02-redis-cache/.env.example
Get-FileHash -Algorithm SHA256 -LiteralPath 'labs/02-redis-cache/.env.example' | Select-Object Algorithm, Hash, Path
git -c safe.directory='<workspace>/java-roadmap' status --short --ignored
```

Expected: both local paths are ignored, the hash matches Task 1, and neither appears in the staged additions.

- [ ] **Step 6: Validate and commit the documentation center**

```powershell
git -c safe.directory='<workspace>/java-roadmap' diff --cached --check
git -c safe.directory='<workspace>/java-roadmap' diff --cached --name-status
git -c safe.directory='<workspace>/java-roadmap' grep --cached -n '\](labs/' -- README.md docs notes interview references compose
```

Expected: diff check has no output and the final command finds no relative Markdown link into the removed `main` `labs/` tree.

Commit only the staged documentation-center migration:

```powershell
git -c safe.directory='<workspace>/java-roadmap' commit -m "refactor(repo): make main a documentation center"
```

---

### Task 4: Build Four History-Preserving Candidate Branches

**Files:**
- Create temporarily: `<workspace>/java-roadmap-branch-rewrite/keep-path.sh`
- Create temporarily: `<workspace>/java-roadmap-branch-rewrite/security/`
- Create temporarily: `<workspace>/java-roadmap-branch-rewrite/redis/`
- Create temporarily: `<workspace>/java-roadmap-branch-rewrite/seckill/`
- Create temporarily: `<workspace>/java-roadmap-branch-rewrite/order/`
- Create in each candidate branch: `.gitignore`

**Interfaces:**
- Consumes canonical source/path pairs:
  - `backup/2026-08-23/redis-before-isolation` → `labs/01-security-rbac`
  - `backup/2026-08-23/redis-before-isolation` → `labs/02-redis-cache`
  - `backup/2026-08-23/seckill-before-isolation` → `labs/03-seckill-inventory`
  - `backup/2026-08-23/order-before-isolation` → `labs/04-order-mq`
- Produces four candidate heads with filtered histories and exact target branch names.

- [ ] **Step 1: Verify and create the temporary rewrite root**

```powershell
$rewriteRoot = '<workspace>\java-roadmap-branch-rewrite'
if (Test-Path -LiteralPath $rewriteRoot) { throw "Rewrite root already exists: $rewriteRoot" }
New-Item -ItemType Directory -Path $rewriteRoot
(Resolve-Path -LiteralPath $rewriteRoot).Path
```

Expected: exact resolved path `<workspace>\java-roadmap-branch-rewrite`. No existing path is overwritten.

- [ ] **Step 2: Create the exact path-filter helper with `apply_patch`**

Create `<workspace>/java-roadmap-branch-rewrite/keep-path.sh` with:

```sh
#!/bin/sh
set -eu
git read-tree --empty
if git cat-file -e "$GIT_COMMIT:$KEEP_PATH" 2>/dev/null; then
  git read-tree --prefix="$KEEP_PATH/" "$GIT_COMMIT:$KEEP_PATH"
fi
```

This script changes only the temporary clone index used by `git filter-branch`.

- [ ] **Step 3: Clone the four canonical sources locally**

```powershell
git clone --no-local --branch backup/2026-08-23/redis-before-isolation '<workspace>\java-roadmap' '<workspace>\java-roadmap-branch-rewrite\security'
git clone --no-local --branch backup/2026-08-23/redis-before-isolation '<workspace>\java-roadmap' '<workspace>\java-roadmap-branch-rewrite\redis'
git clone --no-local --branch backup/2026-08-23/seckill-before-isolation '<workspace>\java-roadmap' '<workspace>\java-roadmap-branch-rewrite\seckill'
git clone --no-local --branch backup/2026-08-23/order-before-isolation '<workspace>\java-roadmap' '<workspace>\java-roadmap-branch-rewrite\order'
```

Expected: four independent clones exist; no remote network access is used.

- [ ] **Step 4: Filter the security history and rename its branch**

```powershell
$env:FILTER_BRANCH_SQUELCH_WARNING = '1'
$env:KEEP_PATH = 'labs/01-security-rbac'
git -C '<workspace>\java-roadmap-branch-rewrite\security' filter-branch --prune-empty --index-filter 'sh /e/test/work/java-roadmap-branch-rewrite/keep-path.sh' -- backup/2026-08-23/redis-before-isolation
git -C '<workspace>\java-roadmap-branch-rewrite\security' branch -m learning/security-rbac
```

Expected: active tree contains only `labs/01-security-rbac` before `.gitignore` is added.

- [ ] **Step 5: Filter the Redis history**

```powershell
$env:KEEP_PATH = 'labs/02-redis-cache'
git -C '<workspace>\java-roadmap-branch-rewrite\redis' filter-branch --prune-empty --index-filter 'sh /e/test/work/java-roadmap-branch-rewrite/keep-path.sh' -- backup/2026-08-23/redis-before-isolation
git -C '<workspace>\java-roadmap-branch-rewrite\redis' branch -m learning/redis-cache
```

Expected: active tree contains only `labs/02-redis-cache` before `.gitignore` is added.

- [ ] **Step 6: Filter the seckill history**

```powershell
$env:KEEP_PATH = 'labs/03-seckill-inventory'
git -C '<workspace>\java-roadmap-branch-rewrite\seckill' filter-branch --prune-empty --index-filter 'sh /e/test/work/java-roadmap-branch-rewrite/keep-path.sh' -- backup/2026-08-23/seckill-before-isolation
git -C '<workspace>\java-roadmap-branch-rewrite\seckill' branch -m learning/seckill-inventory
```

Expected: active tree contains only `labs/03-seckill-inventory` before `.gitignore` is added.

- [ ] **Step 7: Filter the order history**

```powershell
$env:KEEP_PATH = 'labs/04-order-mq'
git -C '<workspace>\java-roadmap-branch-rewrite\order' filter-branch --prune-empty --index-filter 'sh /e/test/work/java-roadmap-branch-rewrite/keep-path.sh' -- backup/2026-08-23/order-before-isolation
git -C '<workspace>\java-roadmap-branch-rewrite\order' branch -m feat/order-mq-reliable-messaging
Remove-Item Env:KEEP_PATH
Remove-Item Env:FILTER_BRANCH_SQUELCH_WARNING
```

Expected: active tree contains only `labs/04-order-mq` before `.gitignore` is added.

- [ ] **Step 8: Add the same safety `.gitignore` to every candidate**

Use `apply_patch` in each clone to create `.gitignore` with exact content:

```gitignore
# Local agent instructions
/AGENTS.md

# Local secrets
.env
.env.*
!.env.example

# Java/Maven output
target/
*.log

# IDE and OS files
.idea/
.vscode/
.DS_Store
Thumbs.db
```

Commit separately in each candidate:

```powershell
git -C '<workspace>\java-roadmap-branch-rewrite\security' add .gitignore
git -C '<workspace>\java-roadmap-branch-rewrite\security' commit -m 'chore(security-rbac): isolate experiment branch'
git -C '<workspace>\java-roadmap-branch-rewrite\redis' add .gitignore
git -C '<workspace>\java-roadmap-branch-rewrite\redis' commit -m 'chore(redis-cache): isolate experiment branch'
git -C '<workspace>\java-roadmap-branch-rewrite\seckill' add .gitignore
git -C '<workspace>\java-roadmap-branch-rewrite\seckill' commit -m 'chore(seckill): isolate experiment branch'
git -C '<workspace>\java-roadmap-branch-rewrite\order' add .gitignore
git -C '<workspace>\java-roadmap-branch-rewrite\order' commit -m 'chore(order-mq): isolate experiment branch'
```

---

### Task 5: Prove Candidate Integrity and Import Refs

**Files:**
- Inspect: four temporary clone histories
- Create local refs: `rewrite/security-rbac`, `rewrite/redis-cache`, `rewrite/seckill-inventory`, `rewrite/order-mq-reliable-messaging`

**Interfaces:**
- Consumes: Task 4 candidate heads and Task 1 backup source refs.
- Produces: reviewed candidate refs in the primary repository.

- [ ] **Step 1: Compare every canonical and rewritten subtree object ID**

Run the source and candidate `rev-parse` commands in pairs:

```powershell
git -c safe.directory='<workspace>/java-roadmap' rev-parse 'backup/2026-08-23/redis-before-isolation:labs/01-security-rbac'
git -C '<workspace>\java-roadmap-branch-rewrite\security' rev-parse 'learning/security-rbac:labs/01-security-rbac'
git -c safe.directory='<workspace>/java-roadmap' rev-parse 'backup/2026-08-23/redis-before-isolation:labs/02-redis-cache'
git -C '<workspace>\java-roadmap-branch-rewrite\redis' rev-parse 'learning/redis-cache:labs/02-redis-cache'
git -c safe.directory='<workspace>/java-roadmap' rev-parse 'backup/2026-08-23/seckill-before-isolation:labs/03-seckill-inventory'
git -C '<workspace>\java-roadmap-branch-rewrite\seckill' rev-parse 'learning/seckill-inventory:labs/03-seckill-inventory'
git -c safe.directory='<workspace>/java-roadmap' rev-parse 'backup/2026-08-23/order-before-isolation:labs/04-order-mq'
git -C '<workspace>\java-roadmap-branch-rewrite\order' rev-parse 'feat/order-mq-reliable-messaging:labs/04-order-mq'
```

Expected: each source/candidate pair is identical. Any mismatch blocks import and push.

- [ ] **Step 2: Enforce each active-tree allowlist**

For each candidate, run `git ls-tree -r --name-only HEAD`. Expected exact prefixes:

```text
.gitignore
labs/01-security-rbac/...
```

and analogously only `labs/02-redis-cache`, `labs/03-seckill-inventory`, or `labs/04-order-mq`. Reject root `README.md`, `AGENTS.md`, any other `labs/` prefix, `docs/`, `notes/`, `interview/`, `references/`, or `compose/`.

- [ ] **Step 3: Verify ignore behavior**

In each clone:

```powershell
git check-ignore -v AGENTS.md labs/01-security-rbac/.env labs/01-security-rbac/target/example.class
```

Use the corresponding experiment path in each clone. Expected: all three paths are ignored; `.env.example` remains tracked.

- [ ] **Step 4: Scan complete candidate histories without printing values**

Scan every commit for private-key headers, GitHub/cloud/API token signatures, authenticated URLs, email/phone/ID patterns, and credential assignments. Reports may contain only commit IDs, paths, category names, and placeholder/test-fixture classification.

Expected: no real credential or personal-information finding. Testcontainers fixed passwords and `.env.example` placeholders are allowed only when explicitly classified.

- [ ] **Step 5: Import candidate objects into primary local refs**

```powershell
git -c safe.directory='<workspace>/java-roadmap' fetch '<workspace>\java-roadmap-branch-rewrite\security' learning/security-rbac:refs/heads/rewrite/security-rbac
git -c safe.directory='<workspace>/java-roadmap' fetch '<workspace>\java-roadmap-branch-rewrite\redis' learning/redis-cache:refs/heads/rewrite/redis-cache
git -c safe.directory='<workspace>/java-roadmap' fetch '<workspace>\java-roadmap-branch-rewrite\seckill' learning/seckill-inventory:refs/heads/rewrite/seckill-inventory
git -c safe.directory='<workspace>/java-roadmap' fetch '<workspace>\java-roadmap-branch-rewrite\order' feat/order-mq-reliable-messaging:refs/heads/rewrite/order-mq-reliable-messaging
git -c safe.directory='<workspace>/java-roadmap' show-ref --heads | Select-String 'refs/heads/rewrite/'
```

Expected: four rewrite refs exist locally and no original branch ref has moved yet.

---

### Task 6: Preserve Legacy Worktrees and Create Isolated Worktrees

**Files:**
- Preserve unchanged: `.worktrees/redis-cache/`
- Preserve unchanged: `.worktrees/seckill-inventory/`
- Preserve unchanged: `.worktrees/order-mq-reliable-messaging/`
- Create: `.worktrees/security-rbac-isolated/`
- Create: `.worktrees/redis-cache-isolated/`
- Create: `.worktrees/seckill-inventory-isolated/`
- Create: `.worktrees/order-mq-isolated/`

**Interfaces:**
- Consumes: four reviewed `rewrite/*` refs.
- Produces: original branch names pointing at isolated histories, with clean isolated worktrees; legacy worktrees remain detached at original commits with all user files preserved.

- [ ] **Step 1: Detach legacy experiment worktrees without changing their trees**

```powershell
git -c safe.directory='<workspace>/java-roadmap/.worktrees/redis-cache' -C .worktrees/redis-cache switch --detach e58183befbc54c7e3cf686f526e0a11afd11c0d2
git -c safe.directory='<workspace>/java-roadmap/.worktrees/seckill-inventory' -C .worktrees/seckill-inventory switch --detach b1b7ffc25d67d0923ff47e390e5517f7aa3ff406
git -c safe.directory='<workspace>/java-roadmap/.worktrees/order-mq-reliable-messaging' -C .worktrees/order-mq-reliable-messaging switch --detach 2ab998bd92f23295b36f6abffede674b8878dfa9
```

Expected: tracked files remain unchanged; Redis and seckill untracked files remain present; each legacy worktree reports detached HEAD at its original SHA.

- [ ] **Step 2: Move canonical local branch refs to reviewed candidates**

```powershell
git -c safe.directory='<workspace>/java-roadmap' branch learning/security-rbac rewrite/security-rbac
git -c safe.directory='<workspace>/java-roadmap' branch -f learning/redis-cache rewrite/redis-cache
git -c safe.directory='<workspace>/java-roadmap' branch -f learning/seckill-inventory rewrite/seckill-inventory
git -c safe.directory='<workspace>/java-roadmap' branch -f feat/order-mq-reliable-messaging rewrite/order-mq-reliable-messaging
```

Expected: backup refs still point to original SHAs; canonical local branches now point to isolated heads.

- [ ] **Step 3: Create new isolated worktrees**

```powershell
git -c safe.directory='<workspace>/java-roadmap' worktree add '.worktrees/security-rbac-isolated' learning/security-rbac
git -c safe.directory='<workspace>/java-roadmap' worktree add '.worktrees/redis-cache-isolated' learning/redis-cache
git -c safe.directory='<workspace>/java-roadmap' worktree add '.worktrees/seckill-inventory-isolated' learning/seckill-inventory
git -c safe.directory='<workspace>/java-roadmap' worktree add '.worktrees/order-mq-isolated' feat/order-mq-reliable-messaging
git -c safe.directory='<workspace>/java-roadmap' worktree list --porcelain
```

Expected: four new worktrees are attached to the four canonical isolated branches; three legacy worktrees remain detached.

- [ ] **Step 4: Reconfirm legacy user files**

Run status in the three legacy worktrees and compare with Task 1. Expected: all previously recorded untracked paths remain and no tracked modification was introduced.

---

### Task 7: Run Independent Experiment Verification

**Files:**
- Test: `.worktrees/security-rbac-isolated/labs/01-security-rbac/`
- Test: `.worktrees/redis-cache-isolated/labs/02-redis-cache/`
- Test: `.worktrees/seckill-inventory-isolated/labs/03-seckill-inventory/`
- Test: `.worktrees/order-mq-isolated/labs/04-order-mq/`

**Interfaces:**
- Consumes: four isolated worktrees from Task 6, JDK 17, and running Docker Engine.
- Produces: fresh Maven verification evidence per isolated branch.

- [ ] **Step 1: Dispatch four Luna-high verification agents in parallel**

Each agent receives exactly one isolated branch, must run from its experiment directory, and may not edit files. Use `gpt-5.6-luna`, reasoning `high`.

- [ ] **Step 2: Run experiment-one verification**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd verify
```

Expected: Maven exits 0 in `labs/01-security-rbac`.

- [ ] **Step 3: Run experiment-two verification**

Run the same command in `labs/02-redis-cache`. Expected: Maven exits 0 with its unit and Testcontainers suites passing.

- [ ] **Step 4: Run experiment-three verification**

Run the same command in `labs/03-seckill-inventory`. Expected: Maven exits 0 with its unit and Testcontainers suites passing.

- [ ] **Step 5: Run experiment-four verification**

Run the same command in `labs/04-order-mq`. Expected: Maven exits 0 with 59 Surefire tests and 26 Failsafe tests, 0 failures/errors/skipped.

- [ ] **Step 6: Review every agent result against branch allowlists**

For each branch, a separate Luna-high reviewer checks Maven evidence, clean status, subtree hash equality, and allowlist compliance. Any failed or skipped verification blocks remote mutation.

---

### Task 8: Final Local Review and Atomic Remote Update

**Files:**
- Inspect: all five candidate refs and GitHub PR #2
- Remote mutation: `origin/main`, `origin/learning/security-rbac`, `origin/learning/redis-cache`, `origin/learning/seckill-inventory`, `origin/feat/order-mq-reliable-messaging`

**Interfaces:**
- Consumes: all Task 7 reviews and exact expected old remote SHAs.
- Produces: documentation-only remote `main`, four isolated remote experiment branches, and closed PR #2.

- [ ] **Step 1: Run final local structural and security review**

Verify:

```powershell
git -c safe.directory='<workspace>/java-roadmap' diff --check origin/main..main
git -c safe.directory='<workspace>/java-roadmap' log --format='%H' origin/main..main -- AGENTS.md
git -c safe.directory='<workspace>/java-roadmap' ls-tree -r --name-only main
git -c safe.directory='<workspace>/java-roadmap' ls-tree -r --name-only learning/security-rbac
git -c safe.directory='<workspace>/java-roadmap' ls-tree -r --name-only learning/redis-cache
git -c safe.directory='<workspace>/java-roadmap' ls-tree -r --name-only learning/seckill-inventory
git -c safe.directory='<workspace>/java-roadmap' ls-tree -r --name-only feat/order-mq-reliable-messaging
```

Expected: main has no `labs/` or `AGENTS.md`; each experiment tree meets its allowlist; all histories pass the redacted sensitive-information scan.

- [ ] **Step 2: Re-read remote heads immediately before push**

```powershell
git ls-remote --heads origin main learning/security-rbac learning/redis-cache learning/seckill-inventory feat/order-mq-reliable-messaging
```

Expected:

- `main` = `04e131dabc41e205e9fb8ade50139ba895e4d558`
- `learning/security-rbac` does not exist
- `learning/redis-cache` = `e58183befbc54c7e3cf686f526e0a11afd11c0d2`
- `learning/seckill-inventory` = `b1b7ffc25d67d0923ff47e390e5517f7aa3ff406`
- `feat/order-mq-reliable-messaging` = `2ab998bd92f23295b36f6abffede674b8878dfa9`

Any difference blocks the push and requires user review.

- [ ] **Step 3: Atomically update all five remote refs with exact leases**

```powershell
git -c safe.directory='<workspace>/java-roadmap' push --atomic origin `
  --force-with-lease=refs/heads/main:04e131dabc41e205e9fb8ade50139ba895e4d558 `
  --force-with-lease=refs/heads/learning/redis-cache:e58183befbc54c7e3cf686f526e0a11afd11c0d2 `
  --force-with-lease=refs/heads/learning/seckill-inventory:b1b7ffc25d67d0923ff47e390e5517f7aa3ff406 `
  --force-with-lease=refs/heads/feat/order-mq-reliable-messaging:2ab998bd92f23295b36f6abffede674b8878dfa9 `
  main:refs/heads/main `
  learning/security-rbac:refs/heads/learning/security-rbac `
  learning/redis-cache:refs/heads/learning/redis-cache `
  learning/seckill-inventory:refs/heads/learning/seckill-inventory `
  feat/order-mq-reliable-messaging:refs/heads/feat/order-mq-reliable-messaging
```

Expected: Git reports one atomic successful update; no backup or `rewrite/*` ref is pushed.

Set and verify the new security branch upstream after the successful push:

```powershell
git -c safe.directory='<workspace>/java-roadmap' branch --set-upstream-to=origin/learning/security-rbac learning/security-rbac
git -c safe.directory='<workspace>/java-roadmap' for-each-ref --format='%(refname:short)|%(upstream:short)|%(objectname)' refs/heads/main refs/heads/learning refs/heads/feat/order-mq-reliable-messaging
```

Expected: every canonical branch tracks its matching `origin/*` ref.

- [ ] **Step 4: Close PR #2 through the GitHub connector**

Update pull request `5Koi1shi4/java-roadmap#2` to state `closed`. Do not delete branches and do not merge the PR.

Expected: PR #2 is closed and unmerged.

- [ ] **Step 5: Verify remote content and PR state**

Read all five remote refs and PR #2 through GitHub/Git. Expected:

- `main` is documentation-only and has no active-tree `AGENTS.md` or `labs/`.
- Each experiment branch has one experiment and no root README/AGENTS/center documents.
- PR #2 is closed and unmerged.
- Local and remote canonical branch heads are identical.
- No GitHub Actions workflow is reported as failed; absence of configured workflows is recorded as such, not called a pass.

---

### Task 9: Cleanup Temporary Rewrite Clones and Document Handoff

**Files:**
- Delete after verification: `<workspace>/java-roadmap-branch-rewrite/`
- Keep: all `backup/2026-08-23/*` refs
- Keep: detached legacy worktrees containing user files
- Update if required: `notes/learning-log.md`

**Interfaces:**
- Consumes: verified remote state from Task 8.
- Produces: clean primary worktree, retained recovery refs, and an explicit handoff of legacy worktree paths.

- [ ] **Step 1: Verify the deletion target before cleanup**

```powershell
$rewriteRoot = (Resolve-Path -LiteralPath '<workspace>\java-roadmap-branch-rewrite').Path
if ($rewriteRoot -ne '<workspace>\java-roadmap-branch-rewrite') { throw "Unexpected cleanup target: $rewriteRoot" }
Get-ChildItem -LiteralPath $rewriteRoot -Force | Select-Object Name, FullName
```

Expected: only the helper and four temporary clones are under the exact rewrite root.

- [ ] **Step 2: Remove only the verified temporary rewrite root**

```powershell
Remove-Item -LiteralPath '<workspace>\java-roadmap-branch-rewrite' -Recurse -Force
Test-Path -LiteralPath '<workspace>\java-roadmap-branch-rewrite'
```

Expected: `False`. This deletion is safe only after Task 8 remote verification and must not target any repository worktree.

- [ ] **Step 3: Produce final status evidence**

```powershell
git -c safe.directory='<workspace>/java-roadmap' status --short --branch
git -c safe.directory='<workspace>/java-roadmap' worktree list --porcelain
git -c safe.directory='<workspace>/java-roadmap' show-ref --heads | Select-String 'backup/2026-08-23/'
Get-FileHash -Algorithm SHA256 -LiteralPath 'labs/02-redis-cache/.env.example' | Select-Object Algorithm, Hash, Path
```

Expected: main has no tracked changes, the dirty local experiment file remains byte-identical but ignored, isolated worktrees are attached to canonical experiment branches, legacy worktrees remain detached, and all four recovery refs still exist.

- [ ] **Step 4: Final report**

Report remote branch URLs and heads, PR #2 closure, Maven results, AGENTS policy, preserved dirty/untracked paths, retained backup refs, detached legacy worktrees, and the GitHub historical-PR caveat. Do not claim old PR objects were erased.
