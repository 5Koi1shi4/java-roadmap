# 面试题库本地化设计

## 背景

当前 `main` 分支将面试题库作为公开项目文档的一部分，并在根 README 和项目手册中提供入口。新的发布要求是：GitHub 上的项目介绍不再包含面试题库，题库内容只在本地保留。同时，已经提交的历史设计与执行计划属于变更轨迹，不为隐藏历史而改写。

## 目标

- 从 `main` 分支删除公开的 `interview/question-bank.md`。
- 删除当前项目介绍和项目手册中的题库入口及目录说明。
- 在被 Git 忽略的本地目录中完整保留题库，避免内容丢失或再次提交。
- 保持其他项目介绍、完整设计流程、技术选型、技术取舍和算法说明不变。
- 将完成后的 `main` 分支同步到 GitHub。

## 不在范围内

- 不重写或删除 `docs/superpowers/specs/` 与 `docs/superpowers/plans/` 中的历史记录。
- 不修改各实验代码、构建配置和运行方式。
- 不修改本地已有的项目经验材料。
- 不清理 Git 历史中的旧版本；GitHub 最新 `main` 不再展示题库，但历史提交仍可追溯。

## 设计方案

### 公开仓库

1. 删除受 Git 跟踪的 `interview/question-bank.md`。
2. 更新根 `README.md`：
   - 从学习资产导航中移除面试题库链接。
   - 从目录结构表中移除 `interview/`。
   - 将实验建设流程中的“面试追问”调整为公开文档语境下的学习与参考记录。
3. 更新 `docs/project-handbook/README.md`：
   - 从目录职责说明中移除 `interview/`。
   - 从相关文档导航中移除面试题库链接。
4. 其他关于技术、算法、设计取舍的正文保持不变；仅移除当前公开入口和题库文件。

### 本地保留

1. 在删除公开文件前，将其完整复制为 `local-only/interview/question-bank.md`。
2. 复用仓库私有排除规则 `.git/info/exclude` 中现有的 `/local-only/`，不把本地题库加入受跟踪的 `.gitignore`。
3. 保留 `local-only/interview/project-experience.md`，不覆盖、不重命名。

### 数据安全与敏感信息

- 本地副本与删除前的公开题库进行 SHA-256 校验，确认内容完全一致。
- 提交前检查待提交差异中是否出现密码、令牌、私钥或真实连接凭据。
- 本地题库不会出现在 `git status` 或 `git ls-files` 中。

## 验证标准

- 本地副本的 SHA-256 与原题库一致。
- `git check-ignore -v local-only/interview/question-bank.md` 能指出 `/local-only/` 排除规则。
- `git ls-files --error-unmatch local-only/interview/question-bank.md` 返回未跟踪结果。
- `README.md` 和 `docs/project-handbook/README.md` 中不再出现公开题库链接或 `interview/` 目录入口。
- `interview/question-bank.md` 在最新 `main` 中不存在。
- 历史规格和计划文档保持原样，允许其中继续记录曾经的题库建设过程。
- `git diff --check` 通过，仓库状态只包含本次预期的公开文档变更；本地私有文件不显示。
- 推送后，本地 `main` 与 `origin/main` 指向同一提交。

## 发布与恢复

本次修改直接在 `main` 上提交并推送。若需恢复公开题库，可从删除前的 Git 历史或本地 `local-only/interview/question-bank.md` 恢复；本地副本不依赖远端仓库。
