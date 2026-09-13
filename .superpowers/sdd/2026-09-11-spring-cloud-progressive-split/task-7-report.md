# Task 7 报告：建立 Eureka 服务发现

## TDD 证据

### RED

命令：

```powershell
.\mvnw.cmd -pl discovery-server,identity-service,legacy-market-service test
```

保留中断测试并首次复跑的正式 RED 输出摘要：

```text
Running com.example.campusmarket.discovery.DiscoveryServerHttpEncodingTest
Tests run: 1, Failures: 1, Errors: 0, Skipped: 0
expected: application/json;charset=UTF-8; but was: application/json

Running com.example.campusmarket.discovery.DiscoveryServerTest
Tests run: 2, Failures: 1, Errors: 0, Skipped: 0
DiscoveryServerTest.serverDeclaresExplicitUtf8ServletEncoding
expected: "UTF-8"; but was: null
discovery-server: Tests run: 3, Failures: 2, Errors: 0, Skipped: 0
identity-service: SKIPPED; legacy-market-service: SKIPPED
```

失败原因是 discovery-server 尚无 `server.servlet.encoding.*` 生产配置，因此
测试观察到属性值为 `null`，真实 HTTP JSON
响应也没有 `charset=UTF-8`。随后将契约测试扩展为测试专用中文 JSON 端点，并再次执行：

```text
.\mvnw.cmd -pl discovery-server -Dtest=DiscoveryServerTest,DiscoveryServerHttpEncodingTest test
discovery-server: Tests run: 4, Failures: 3, Errors: 0, Skipped: 0
```

该次 RED 中健康检查和中文端点均观察到 `application/json`，新增端点的正文断言尚未执行到，
失败仍明确来自缺少 UTF-8 响应头配置。

### GREEN

仍使用上述命令执行；首次依赖解析因沙箱不能写用户 `.m2`，随后以提升权限重试完成依赖下载。

准确结果：

```text
discovery-server: Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
identity-service: Tests run: 24, Failures: 0, Errors: 0, Skipped: 0
legacy-market-service: Tests run: 134, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS; 162/162 passed, 0 failure, 0 error, 0 skip
```

聚焦 Discovery GREEN 命令为：

```powershell
.\mvnw.cmd -pl discovery-server -Dtest=DiscoveryServerTest,DiscoveryServerHttpEncodingTest test
```

结果为 4/4 通过，0 failure、0 error、0 skip。

## 实现与文件

- discovery-server：加入 Eureka Server、Web、Actuator 依赖；新增启动类；固定端口 8761，关闭本地单节点自注册和拉取，仅暴露 health/info；显式配置 `server.servlet.encoding` 为 UTF-8；新增配置契约测试和真实 HTTP 中文 JSON 编码测试。
- identity-service：加入 Eureka Client 依赖；固定 `spring.application.name=identity-service`，使用 `${EUREKA_DEFAULT_ZONE:http://localhost:8761/eureka/}`；test profile 禁用 Eureka client，避免测试后台注册。
- legacy-market-service：加入 Eureka Client 依赖；固定 `spring.application.name=legacy-market-service`，使用同一可覆盖默认 zone；test profile 禁用 Eureka client，避免测试后台注册。
- 两业务服务新增的 `ServiceIdConfigurationTest` 均通过真实 `ApplicationContextRunner` 与配置数据初始化器读取生产配置。
- 保留未跟踪的用户文件 `legacy-market-service/src/test/.../DockerfilePathGuardTest.java`，未暂存、未修改。

## 自审与验证

- 依赖版本由父 POM 统一管理（Boot 3.5.16、Cloud 2025.0.3），未引入 Java 业务模型或跨库逻辑。
- 仅修改 Task7 允许的 discovery-server、identity-service POM/application.yml，以及允许的测试文件。
- `git diff --check`：退出码 0，无空白错误。
- GREEN 日志中的既有 `@MockBean` 弃用、LoadBalancer 默认缓存及测试业务日志警告未作无关修改。
- 本轮提交命令：`git commit -m "fix(cloud): enforce discovery HTTP UTF-8"`。

## Concerns

未发现阻塞项。按任务要求未运行 Docker/集成 suite。
