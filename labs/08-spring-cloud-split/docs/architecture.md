# 8.1 身份拆分架构

四个应用分别启动自己的 WebServer 和 Spring 上下文：`discovery-server` 提供 Eureka 注册表，`api-gateway` 提供客户端入口，`identity-service` 负责验证码、注册、登录和 RS256/JWKS，`legacy-market-service` 保留实验七的交易闭环。

Gateway 的身份路由为 `/api/auth/** → lb://identity-service`，业务路由为 `/api/** → lb://legacy-market-service`，身份路由优先匹配。服务实例通过 Eureka 获取；关闭 discovery locator，避免自动公开 `/{serviceId}/**`。JWKS 的实际身份端点是 `/api/auth/.well-known/jwks.json`。

Gateway 校验 Bearer Token，移除客户端伪造的身份与转发 Header，并生成关联 ID。兼容单体仍独立验证同一个 Token，不信任 Gateway 传入的用户 Header。缺失或无效身份为 401，普通用户访问管理员接口为 403；路由目标故障返回固定中文 UTF-8 503，不传递下游异常体。

身份数据库和交易数据库的账号相互隔离。身份领域模型不作为业务模块的生产 Java 依赖；测试支持模块仅在 test scope 提供临时 RSA 密钥及 HTTP/数据库夹具。交易业务中的库存、幂等、Outbox/Inbox、支付与售后事务继续在交易库内部完成。

身份服务停止后，已经获取公钥的资源服务器可继续验证未过期 Token；这不表示可以继续登录，也不表示冷启动时可以跳过验签。Eureka 缓存、JWKS 缓存、目标停机恢复与冷启动 readiness 必须由真实网络故障测试证明。当前文档描述设计边界，最终验收状态以 README 的新鲜测试证据为准。
