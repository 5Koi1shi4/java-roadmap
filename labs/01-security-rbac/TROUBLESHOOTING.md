# 实验一排障复盘：JWT 与 RBAC 权限服务

本文记录本次实验从本地环境搭建、认证接口验证到 GitHub 发布时遇到的问题。每项按“现象—原因—解决—预防”整理。文档中的 `*` 表示账号、密码、哈希、token 或密钥等脱敏值；不能将真实值提交到仓库。

## 阅读提示

- 反引号包围的内容是命令、文件名、环境变量、端口、错误文本或代码变量，例如 `docker compose config`、`.env`、`MYSQL_PORT`。
- 将 Markdown 复制为纯文本时，反引号样式可能被去掉，命令与中文会显得粘连。应以原始 Markdown 或渲染预览为准。
- 在 VS Code 中打开本文件后按 `Ctrl+Shift+V` 可查看完整预览；在 GitHub 仓库中打开本文件可直接阅读渲染版本。
- 下文的“现象—原因—解决—预防”分别对应：看到什么、为什么发生、如何操作、以后如何避免。

## 1. Docker Compose 无法映射 MySQL 端口

**现象：** 执行 `docker compose up -d` 时，Docker 报错 `ports are not available`。这表示宿主机端口 `3306` 已被其他程序占用。

**原因：** 本机已有 MySQL 服务监听 3306。只修改 `compose.yaml` 中的默认端口并不一定生效，因为 `.env` 中的 `MYSQL_PORT` 会覆盖默认值。

**解决：**

```powershell
netstat -ano | findstr :3306
docker compose config
```

保留本机 MySQL，将实验容器映射为 3307；同时更新 `.env` 的 `MYSQL_PORT`，并让应用数据源连接 `localhost:3307`。

**预防：** 每次修改 Compose 变量后使用 `docker compose config` 查看最终配置；不要只看 YAML 中的默认值。

## 2. MySQL 容器创建失败或健康检查失败

**现象：** 容器无法创建，或虽然启动但持续 `unhealthy`。

**原因：**

- MySQL 官方镜像不允许 `MYSQL_USER=root`；root 密码应由 `MYSQL_ROOT_PASSWORD` 配置。
- `mysqladmin ping` 默认验证 root，若健康检查误用应用用户密码或不一致的默认值，会失败。
- 浮动镜像标签会降低复现性。

**解决：** 使用普通应用用户与独立 root 密码；健康检查使用 `MYSQL_ROOT_PASSWORD`；将镜像固定到 `mysql:8.0.46`。提交 `.env.example`，但忽略真实 `.env`。

**预防：** 启动后执行 `docker compose ps` 和 `docker compose logs mysql`，确认服务为 `healthy` 后再启动应用。

## 3. Windows 找不到 `mysql` 命令

**现象：** PowerShell 提示 `mysql` 不是可识别的命令。

**原因：** Windows 没有安装 MySQL Client，或其目录未加入 PATH。

**解决：** 使用容器自带客户端：

```powershell
docker compose exec mysql mysql -u * -p *
```

**预防：** 本实验的 README 优先给出容器内客户端命令，避免把本机安装 MySQL Client 当作前置条件。

## 4. 本地 `admin` 用户存在，但登录仍返回 401

**现象：** 数据库中可见 `admin`、`enabled=1`，登录接口仍返回 401。

**原因：** `password_hash` 的格式正确不代表它与当前输入密码匹配。认证服务实际调用 `BCryptPasswordEncoder.matches(rawPassword, storedHash)`；将占位符、空格或错误密码写入哈希字段都会失败。

**解决：** 使用项目依赖的 Spring Security BCrypt 生成新哈希，并先确认 `matches` 返回 `true`，再更新 `sys_user.password_hash`。生成哈希时必须同时提供 `spring-security-crypto` 的依赖和 `spring-jcl`，否则 JShell 会出现 `LogFactory` 类缺失。

**预防：** 不提交固定管理员密码或哈希；以 `.env`、本地脚本或开发 profile 管理初始化信息。

## 5. 登录请求收到 401 或 JSON 解析错误

**现象：**

- 在浏览器地址栏访问 `/api/auth/login` 得到 401；
- 手工拼接请求体时日志出现 `HttpMessageNotReadableException`；
- PowerShell `curl.exe` 报 URL 格式错误。

**原因：** 登录端点只接受带 JSON 请求体的 `POST`；浏览器地址栏只能发送 GET。PowerShell 的引号、反引号续行和手工 JSON 容易改变参数边界。

**解决：** 用对象加 `ConvertTo-Json -Compress` 构造 JSON，并使用 `Invoke-RestMethod`：

```powershell
$password = Read-Host '输入本地开发密码'
$body = @{ username = 'admin'; password = $password } | ConvertTo-Json -Compress
$response = Invoke-RestMethod `
  -Uri 'http://127.0.0.1:8080/api/auth/login' `
  -Method Post -ContentType 'application/json' -Body $body
```

不要单独打印 `$body`，否则会将明文密码显示在终端。

**预防：** README 明确区分“浏览器访问页面”和“客户端调用 JSON API”。控制器同时校验空用户名、空密码和空 refresh token，避免它们演变为 500。

## 6. 不清楚 access token、refresh token 与状态码含义

**现象：** 登录成功后不知道哪一行可用于访问接口；刷新、注销后看到 PowerShell 的 401 异常，误以为失败。

**原因：** `Invoke-RestMethod` 的赋值操作默认不输出响应体；`204 No Content` 本身是注销成功的标准响应；被撤销或已轮换的 refresh token 再使用时，401 是预期安全行为。

**解决：**

- `$response.accessToken` 用于 `Authorization: Bearer *` 访问受保护接口；
- `$response.refreshToken` 只用于 `/api/auth/refresh` 与 `/api/auth/logout`；
- refresh 成功后保存 `$refreshed.refreshToken`，旧 refresh token 应立即失效；
- logout 返回 204，随后 refresh 返回 401，即完成验证。

**预防：** 在测试与 README 中完整覆盖“登录—访问—刷新—注销—刷新失败”链路。

## 7. 同一 refresh token 被并发刷新两次

**现象：** 初版实现中，两个并发 refresh 请求都可能返回 200，并分别得到新的有效 token。

**原因：** “先查询是否可用，再撤销”的读写分离存在竞态：两个请求都可能在写入前读到未撤销的会话。

**解决：** 在事务中用条件更新原子消费旧 token：

```sql
UPDATE refresh_token
SET revoked = TRUE
WHERE token_hash = ?
  AND revoked = FALSE
  AND expires_at > ?;
```

只有受影响行数为 1 的请求才能签发 successor token。`AuthFlowE2ETest` 用真实 MySQL 和并发 HTTP 请求断言恰好一个 200、一个 401。

**预防：** 安全状态转换优先使用数据库的条件更新或唯一约束表达原子语义，不依赖应用层“先查后写”。

## 8. JWT 密钥配置与重启行为不一致

**现象：** YAML 中存在 `jwt.secret`，但初版 Bean 只读取 `System.getenv("JWT_SECRET")`；未配置时随机生成密钥，进程重启后未过期 access token 也会失效。

**原因：** 配置来源与 Bean 实现脱节，且随机密钥不能支持稳定重启或多实例验证。

**解决：** 将 `JWT_SECRET` 绑定到 `jwt.secret`，要求其为可解码为至少 32 字节的 Base64 值；缺失、格式错误或长度不足时拒绝启动。新增配置单元测试和端到端测试专用密钥。

**预防：** 将敏感值放入被忽略的 `.env` 或部署环境变量；提交 `.env.example` 说明格式，不提交真实值。

## 9. Git 提示 dubious ownership

**现象：** 在 Windows 磁盘上执行 Git 时提示 `detected dubious ownership`，拒绝访问仓库。

**原因：** 该文件系统不记录 POSIX 所有者信息，Git 的目录安全检查无法确认仓库归属。

**解决：** 仅将当前已知项目目录加入 Git 的可信目录：

```powershell
git config --global --add safe.directory E:/test/work/java-roadmap
```

**预防：** 不要为磁盘根目录或通配路径设置 `safe.directory`，只添加明确的项目路径。

## 10. Git push 被远程提交或网络阻断拒绝

**现象：**

- 首次 push 提示 `fetch first`；
- rebase 期间 `.gitignore` 显示 `both added`，Windows 无法删除 `notes` 目录；
- `git ls-remote` 或 push 报 `Connection was reset`、无法连接 `github.com:443`。

**原因：** 远程仓库已存在初始提交，需要先整合；`.gitignore` 是双方新增文件，需要手动合并；网络环境可以解析和 Ping GitHub，但 TCP 443 被阻断或代理未配置。

**解决：**

```powershell
git pull --rebase origin main
# 手动合并 .gitignore，删除冲突标记并保留两侧有效规则
git add .gitignore
git rebase --continue
git push -u origin main
```

目录删除失败时选择 `n` 停止重试，再用 `git status` 确认 rebase 状态；不要手动删除整个 `notes` 目录。网络失败时用 `Test-NetConnection github.com -Port 443` 诊断，切换至可访问 GitHub HTTPS 的网络，或配置已启用代理工具提供的本地 HTTP 代理端口。

**预防：** 推送前先执行 `git remote -v`、`git status`；不要使用 `git push --force` 覆盖远程提交。

## 最终验收证据

- `mvnw.cmd verify`：19 个测试通过，0 失败、0 跳过；
- Docker/Testcontainers：真实 MySQL、Flyway、随机 HTTP 端口验证通过；
- 本地手动链路：登录 200、管理员访问 200、刷新 200、注销 204、已撤销 refresh token 返回 401；
- GitHub：本地 `main` 已推送并跟踪 `origin/main`。
