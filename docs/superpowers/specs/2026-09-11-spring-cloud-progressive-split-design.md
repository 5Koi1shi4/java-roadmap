# 实验八 Spring Cloud 渐进拆分设计

## 1. 目标与阶段范围

实验八以实验七已经验收的校园二手交易平台为业务基线，在不扩大业务范围、不削弱既有正确性证明的前提下，学习并验证 Spring Cloud 下的服务发现、网关路由、身份边界、独立数据所有权、跨进程认证和故障隔离。

实验八使用独立分支 `learning/spring-cloud-split`，代码只存在于 `labs/08-spring-cloud-split/`。实验七的 `learning/campus-market` 分支及 `labs/07-campus-market/` 保持不变。

本规格只定义第一个可独立验收的里程碑 8.1：

- 建立 Eureka 服务发现中心；
- 建立 Spring Cloud Gateway API 网关；
- 从实验七模块化单体中抽出身份服务；
- 将其余业务作为兼容单体 `legacy-market-service` 运行；
- 通过真实 HTTP、独立数据库账号、JWT 非对称签名和故障测试证明边界成立。

里程碑 8.1 不拆商品、订单、支付或履约服务，不引入 Config Server、OpenFeign、分布式事务、Kubernetes、真实 CAS、真实支付、真实物流，也不恢复实验七已经暂停的聊天、竞价和跑腿/代取扩展。后续只在 8.1 验收后逐个设计并拆分稳定模块，不提前创建空服务。

## 2. 技术基线

- JDK 固定为 17；
- Spring Boot 固定为 `3.5.16`；
- Spring Cloud BOM 固定为 `2025.0.3`；
- Maven Wrapper 使用仓库内版本，不依赖机器专属 Maven；
- Spring Cloud Gateway 使用 WebFlux 版本；
- 服务发现使用 Spring Cloud Netflix Eureka；
- MySQL、Redis、RabbitMQ、Elasticsearch、MinIO、Toxiproxy 版本继承实验七的已验收基线；
- 单元测试使用 JUnit 5、AssertJ 与 Spring Security Test；外部协作使用 Testcontainers 和真实 HTTP。

Spring Cloud 官方兼容表将 2025.0.x 对应到 Spring Boot 3.5.x；`2025.0.3` 是该发布列车的最终开源版本，并包含 Spring Cloud Gateway 4.3.5、Spring Cloud Netflix 4.3.3。该发布列车已经结束开源支持，但实验受 `Spring Boot 3.x` 与 JDK 17 基线约束，因此固定使用最终补丁版本并在 README 明示此边界，不静默升级到 Spring Boot 4。

参考资料：

- <https://spring.io/projects/spring-cloud/>
- <https://spring.io/blog/2026/06/11/spring-cloud-2025-0-3-aka-northfields-has-been-released/>
- <https://docs.spring.io/spring-cloud-netflix/reference/spring-cloud-netflix.html>

## 3. 总体架构

```text
client
  │
  ▼
api-gateway ─────► identity-service ─────► identity_db
  │                    │
  │                    └─ RS256 签发 / JWKS 公钥发布
  ▼
legacy-market-service ────────────────► market_db
  │
  ├─ 商品、订单、支付、履约、争议、质保
  ├─ RabbitMQ / Elasticsearch / MinIO
  └─ 独立验证 Bearer Token
       ▲
       │
discovery-server（Eureka）
```

客户端只访问 `api-gateway`。Gateway 与两个业务进程都注册到 Eureka；Gateway 使用逻辑服务名和负载均衡 URI 路由，不写死业务实例端口，也不启用自动 DiscoveryClient 路由。

Gateway 只定义以下公开路由：

- `/api/auth/**` → `lb://identity-service`；
- 其余已有 `/api/**` → `lb://legacy-market-service`；
- `/api/auth/.well-known/jwks.json` → `lb://identity-service`，只返回当前验证公钥；
- Gateway 自身只公开最小的存活和就绪状态，不转发 Eureka 管理页、内部 Actuator 详情或任意 `/{serviceId}/**` 路径。

身份服务和兼容单体均可绕过 Gateway 接收内部网络请求，因此下游必须自行验证 Bearer Token。任何服务都不得把 Gateway 注入的用户 ID、角色或认证成功 Header 作为身份事实。

## 4. Maven 模块与职责

`labs/08-spring-cloud-split/` 使用一个聚合 Maven 工程：

| 模块 | 职责 | 明确不负责 |
|---|---|---|
| `discovery-server` | Eureka 注册中心、最小健康检查 | 业务路由、身份认证、业务数据 |
| `api-gateway` | 显式路由、Bearer Token 初次验证、Header 清洗、关联 ID、超时和统一边界错误 | 签发 Token、业务权限规则、数据库访问 |
| `identity-service` | 验证码、注册、登录、校园邮箱约束、用户状态、JWT 私钥签发和 JWKS | 商品与交易查询、替其他服务授权 |
| `legacy-market-service` | 承接尚未拆分的商品、订单、支付、履约、争议、质保、可靠消息、搜索和文件能力 | 身份表、密码、验证码、JWT 私钥 |
| `platform-test-support` | 跨模块测试夹具、进程启动辅助、RSA 测试密钥和安全断言 | 生产配置、生产业务逻辑 |

生产代码不依赖 `platform-test-support`。共享模块中不得放置可由两个服务共同修改的领域实体、Repository 或数据库模型；跨服务稳定契约通过 HTTP 请求/响应、JWT 声明和逻辑 ID 表达。

## 5. 数据所有权与迁移

### 5.1 身份库

`identity-service` 使用独立的 `identity_db` 和独立 Flyway 历史，只拥有：

- `campus_user`；
- `email_verification`；
- `external_identity`。

身份库账号只拥有 `identity_db` 权限，不能读取或写入 `market_db`。

### 5.2 交易库

`legacy-market-service` 使用 `market_db`，保存实验七除身份三张表之外的全部业务事实。原先引用 `campus_user(id)` 的数据库外键移除，但列名、`CHAR(36)` 类型、非空约束、业务索引和服务层授权规则保留。`seller_id`、`buyer_id`、案件参与者等字段只表示身份服务签发的不可解释 UUID，兼容单体不得通过跨库 SQL 补查邮箱、密码、状态或角色。

交易库账号只拥有 `market_db` 权限，不能读取或写入 `identity_db`。

### 5.3 初始化边界

8.1 只支持全新实验环境和测试夹具初始化，不修改实验七数据库，不实现生产不停机迁移、双写或存量数据回填工具。测试使用同一 MySQL Testcontainer 内的两个 database/schema 和两个最小权限账号，以实际拒绝访问证明所有权边界。README 必须明确此限制，不能将全新初始化描述为生产迁移方案。

## 6. HTTP 与身份契约

验证码、注册和登录保持实验七的路径、请求字段、响应字段、状态码和 `application/json; charset=UTF-8`：

- `POST /api/auth/email-verifications`；
- `POST /api/auth/register`；
- `POST /api/auth/login`。

现有客户端只把基地址切换到 Gateway，不改变业务请求。注册成功仍返回 201；登录成功仍返回 `accessToken`、`tokenType=Bearer`、`expiresIn=900`、`userId` 和 `roles`。重复邮箱返回 409，验证码或凭据无效返回 401，请求非法返回 400，限流返回 429，依赖不可用返回 503。

身份 HTTP 请求启用严格 JSON 反序列化：未知字段、错误类型、缺失必填字段、非法邮箱、非法用途和越界密码均返回 400，服务层不负责清洗协议数据。公开注册和登录只签发 `ROLE_USER`；8.1 不新增管理员创建或角色管理接口。实验七需要管理员角色的回归与旅程测试使用仅存在于测试资源中的 RSA 私钥夹具签发 `ROLE_ADMIN` Token，生产身份服务不会接受客户端自报角色。

Access Token 固定采用 RS256，固定有效期 15 分钟。声明契约为：

- `sub`：合法 UUID 字符串；
- `roles`：非空集合，只允许 `ROLE_USER`、`ROLE_ADMIN`；
- `iat`：签发时间；
- `exp`：严格等于 `iat + 15 分钟`；
- `iss`：配置项 `campus.market.jwt.issuer`，所有验证方必须精确匹配；
- `aud`：固定为 `campus-market-api`；
- JWT Header `alg`：只能是 `RS256`；
- JWT Header `kid`：必须对应当前 JWKS 中的已知验证公钥。

身份服务从只读密钥文件或环境注入的密钥材料加载固定 RSA 私钥，禁止启动时随机生成生产密钥，禁止把私钥、真实 Token 或示例密钥提交到仓库。测试密钥只存在于测试资源。JWKS 只发布 RSA 公钥参数和 `kid`，不发布私钥字段。

Gateway 和兼容单体在内存中缓存 JWKS。遇到未知 `kid` 时只允许刷新一次；仍未知则返回 401，不能无限刷新。已缓存公钥对应的未过期 Token 在身份服务短暂不可用且验证进程未重启时继续有效。验证进程冷启动且 JWKS 不可达时必须保持未就绪，不得降级为跳过验签。

## 7. Gateway 安全与请求处理

Gateway 对所有进入请求执行以下固定顺序：

1. 删除客户端提供的 `X-User-Id`、`X-User-Roles`、`X-Authenticated-User`、`X-Internal-*` 和 `X-Correlation-Id`；
2. 生成随机、低信息量的 `X-Correlation-Id` 并转发；
3. 对登录、注册、验证码和 JWKS 路径允许匿名访问；
4. 对其余 `/api/**` 验证 Bearer Token；
5. 将原始 Bearer Token转发给下游，下游再次验签并建立自己的安全上下文；
6. 通过显式 Eureka 路由调用目标服务；
7. 对边界异常生成统一 UTF-8 JSON，不把内部异常体直接透传给客户端。

业务角色判断仍由目标服务负责。缺失或无效身份返回 401；身份有效但权限不足返回 403。Gateway 不自动重试验证码、注册、登录或其他非幂等 POST；8.1 不为任何写请求配置重试。后续若为 GET 增加重试，必须另行规定次数、超时和可观测性。

不信任公网传入的 `Forwarded`、`X-Forwarded-*` 或设备/IP Header。仅当部署配置给出明确的可信代理网段时才接受转发地址；本地与测试环境默认使用直连来源地址。

## 8. 错误处理与故障语义

所有边界错误显式返回 `application/json; charset=UTF-8`，使用稳定的 `ApiError` 结构。响应、日志和指标不得包含内部主机名、端口、数据库名、SQL、堆栈、Token、私钥、公钥原文、密码、验证码或对象存储 key。

- Eureka 中无可用身份实例、身份服务连接失败或身份库不可用：身份相关请求返回 503；
- Eureka 中无可用兼容单体实例或连接失败：对应业务请求返回 503；
- 无效、过期、篡改、错误签发者、错误受众、未知算法、非法角色或未知 `kid`：返回 401；
- 已认证但业务权限不足：返回 403；
- Eureka 短暂不可用但 Gateway 已缓存有效注册信息时，可继续使用缓存路由；冷启动无法取得注册表时保持未就绪；
- 身份服务停止后，新的验证码、注册和登录失败；已经缓存公钥的 Gateway 与兼容单体仍可验证未过期 Token。

Gateway 与下游均设置有限连接和响应超时。8.1 不使用断路器伪造业务降级数据，不把依赖故障伪装成 404、401 或空成功响应。

## 9. 可观测性

四个运行模块均提供 Actuator 存活和就绪探针。公开 Gateway 只暴露聚合后的最小状态，详细组件健康信息只在内部端口或测试上下文读取。

Gateway 至少记录以下低基数指标：

- 按固定路由 ID 和结果分类统计请求；
- 401、403、503 数量；
- 路由目标不可用次数；
- JWKS 刷新成功、失败和未知 `kid` 次数；
- 请求耗时直方图。

标签不得包含用户 ID、Token、邮箱、IP、URL 查询值、实例地址、异常消息或关联 ID。关联 ID 只用于安全日志串联，不接受客户端覆盖，也不作为指标标签。

## 10. 测试策略

所有功能遵循红—绿—重构：先写能因缺少行为而失败的测试，确认失败原因，再写最小实现。

### 10.1 单元测试

- 显式路由只包含允许的公开路径；
- 敏感 Header 被删除，关联 ID 由 Gateway 重新生成；
- JWT 签发和验证覆盖算法、`kid`、`iss`、`aud`、`sub`、角色、`iat`、`exp` 与固定 15 分钟期限；
- 身份请求对未知字段、错误类型、缺失字段和非法值快速失败；
- 401、403、503 均返回稳定 UTF-8 JSON；
- 写请求不存在自动重试配置；
- 身份领域、验证码、注册和登录行为保持实验七基线。

### 10.2 数据边界集成测试

Testcontainers 启动真实 MySQL，为 `identity_db` 和 `market_db` 创建独立账号。测试必须证明：

- 两套 Flyway 历史分别成功；
- 身份账号访问交易表被 MySQL 拒绝；
- 交易账号访问身份表被 MySQL 拒绝；
- 交易 schema 不包含对身份 schema 的外键；
- 交易流程仅凭 JWT 中的 UUID 完成授权和业务操作。

### 10.3 跨服务真实 HTTP 旅程

测试以独立 Spring 应用上下文和随机端口启动 Eureka、Gateway、身份服务与兼容单体，并从 Gateway 发起真实 HTTP：

1. 请求验证码；
2. 注册用户；
3. 登录取得 Token；
4. 使用 Token 创建商品；
5. 验证响应字段、状态码、中文内容和 UTF-8 Content-Type；
6. 验证 Eureka 中存在身份和兼容单体实例；
7. 验证 Gateway 路由使用服务 ID，而不是测试观察到的随机端口。

### 10.4 安全测试

- 客户端伪造内部身份 Header 不改变实际身份；
- 缺失、篡改、过期、错误受众、错误签发者、未知算法、非法角色和未知 `kid` 均返回 401；
- 普通用户访问管理员接口返回 403；
- 直接访问兼容单体时仍必须提供有效 Token；
- JWKS 不含私钥参数；
- 错误响应与日志扫描不出现内部地址、堆栈或敏感字段。

### 10.5 故障测试

- 先完成一次登录并预热两级 JWKS 缓存，再停止身份服务：登录返回 503，已有 Token 仍能访问兼容单体；
- 停止兼容单体：Gateway 返回安全 503；
- 停止 Eureka：已注册并缓存的路由可在明确的缓存窗口内继续工作，新的冷启动实例保持未就绪；
- 恢复目标服务后，路由在有界时间内自动恢复，无需修改配置或重启 Gateway。

### 10.6 回归测试

实验七迁入 `legacy-market-service` 的商品、库存、订单、支付、退款、履约、争议、质保、可靠消息、搜索、文件与故障恢复测试不得删除或降级为 Mock。测试包名和夹具可为模块化而调整，但原有业务不变量与真实外部协作覆盖必须保留。

## 11. 验收标准

在 `labs/08-spring-cloud-split/`、JDK 17 和可用 Docker Engine 下执行：

```powershell
docker info
.\mvnw.cmd test
.\mvnw.cmd verify
git diff --check
```

验收必须同时满足：

- Surefire 与 Failsafe 均为 0 failures、0 errors、0 skipped；
- 两库权限隔离、真实 HTTP 旅程、安全测试和故障测试全部执行；
- 实验七迁入的业务回归全部通过；
- Eureka、Gateway、身份服务和兼容单体的边界由测试证明；
- 日志扫描没有敏感信息、意外重连噪声或被吞掉的外部异常；
- README、`TROUBLESHOOTING.md`、学习日志、面试追问和测试数量证据完整；
- 实验分支活动树只包含根 `.gitignore` 与 `labs/08-spring-cloud-split/`，不包含其他实验或 `main` 文档中心内容。

Docker 不可用、外部测试 skipped、使用本机历史服务代替 Testcontainers，或只验证单进程 Mock 路由，均不算通过。

## 12. 实施与后续顺序

8.1 按以下依赖顺序实施：

1. 创建实验八独立分支、工作树、聚合构建和基线测试；
2. 建立两个数据库及最小权限；
3. 以测试先行迁出身份领域和身份 HTTP 契约；
4. 将 JWT 改为 RS256、建立 JWKS 和双重验证；
5. 建立 Eureka 与显式 Gateway 路由；
6. 迁入兼容单体并移除身份表依赖；
7. 完成跨服务旅程、安全测试和故障演练；
8. 复跑完整实验七业务回归；
9. 补齐运行、排障、学习和面试文档后验收。

8.1 验收后，实验八按“商品 → 订单 → 支付 → 履约”的顺序逐项重新设计。每次只迁移一个稳定边界，要求拥有独立数据、明确同步/异步契约、故障语义和完整回归；不得在同一里程碑同时扩大业务功能。
