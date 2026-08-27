# Task 7 实现报告

## 范围

实现逻辑文件私有 ACL、授权/撤权幂等、统一隐藏资源 404、审计 fail-close 事务边界、逻辑删除与 Blob 引用计数/幂等清理任务，并接入真实 Spring HTTP Controller 与明确 wiring Bean。

## 关键保证

- 读取通过单条 `stored_file`/`stored_blob`/`file_grant` 查询同时完成存在性和权限判断；不可读、缺失、已删除统一抛出 `ResourceHiddenException`。
- 只有 owner 可授权、撤权、删除；grantee 只读；管理员/其他身份没有旁路；自身授权/撤权返回 400。
- grant/revoke/delete/read 成功与拒绝均写固定字段审计；审计异常向上抛出，使事务回滚并由边界映射 503。
- 删除按逻辑文件到 Blob、授权、清理任务的顺序执行；仅 ACTIVE→DELETED 成功时减少引用；引用归零时 PENDING_DELETE 与唯一代次任务同事务提交。
- API 响应仅返回逻辑文件元数据，不包含 owner、Blob ID、Key、哈希或去重信息。

## 验证

- `./mvnw.cmd '-Dtest=FileAccessServiceTest,FileServiceWiringConfigurationTest' test`：3 tests，0 failures，0 skipped。
- `./mvnw.cmd test`：42 tests，0 failures，0 skipped。
- 提权 `./mvnw.cmd clean verify`：42 unit + 38 integration tests，0 failures，0 skipped；真实 MySQL 8.4 Testcontainer/Flyway 验证通过。
- `git diff --check`：通过（仅报告 Windows 工作树换行提示）。
