# 排障手册

## SmartCN 插件或 Elasticsearch 启动失败

`analysis-smartcn` 必须与 Elasticsearch 服务端版本完全一致。本实验固定使用 `8.18.8`，插件由 `docker/elasticsearch/Dockerfile` 在同一个 `8.18.8` 基础镜像内安装，不要把其他版本插件复制进容器。

先重新构建并检查插件：

```powershell
docker compose build --no-cache elasticsearch
docker compose run --rm elasticsearch bin/elasticsearch-plugin list
docker compose logs elasticsearch
```

列表中应包含 `analysis-smartcn`。不要用宿主机插件目录覆盖容器插件目录。

## Docker 内存不足或容器反复退出

Elasticsearch 容器固定使用 `-Xms512m -Xmx512m`，Docker Desktop 还需要给 MySQL、测试 JVM 和构建留出空间。出现退出码 137、OOM 或容器健康检查超时时，先关闭无关容器并增加 Docker Desktop 可用内存，再重试；不要通过跳过集成测试获得“通过”。

```powershell
docker compose ps
docker compose logs mysql
docker compose logs elasticsearch
```

## 单节点集群为 yellow

单节点上，如果索引副本数大于 0，副本无法分配会得到 yellow。本实验索引模板把副本数设为 0；yellow 不等于 SmartCN 或主分片不可用，但应检查是否手工创建了不符合模板的索引。

```powershell
curl.exe 'http://localhost:9200/_cluster/health?pretty'
curl.exe 'http://localhost:9200/products-*/_settings?pretty'
```

只对一个人工确认的明确物理索引修正副本数，禁止对通配符执行写操作：

```powershell
curl.exe -X PUT 'http://localhost:9200/products-v0123456789abcdef0123456789abcdef/_settings' `
  -H 'Content-Type: application/json' `
  --data-binary '{"index":{"number_of_replicas":0}}'
```

## strict mapping 冲突

商品索引使用 `dynamic: strict`。出现 `strict_dynamic_mapping_exception` 通常表示 Outbox payload 或索引模板字段不一致，而不是应该临时放宽 mapping。检查失败 Outbox 的 `last_error`、商品版本和 `src/main/resources/elasticsearch/products-index.json`；修正生产者/模板后，通过运维接口修复商品或重投 `FAILED` 事件。不要直接向索引塞入未知字段，也不要把 mapping 改成 dynamic 来掩盖契约错误。

## read/write alias 分裂或缺失

系统要求 `products-read` 与 `products-write` 各自只有一个目标且目标相同。启动恢复和切换遇到分裂、缺失或多目标状态会 fail closed：保持 dispatcher 暂停或启动失败，不会由 bootstrap 猜测覆盖。

先只读检查证据：

```powershell
curl.exe 'http://localhost:9200/_alias/products-read?pretty'
curl.exe 'http://localhost:9200/_alias/products-write?pretty'
```

同时查询 MySQL 的 `search_coordination.active_rebuild_id`、`dispatcher_paused` 和对应 `search_rebuild_job` 的 `source_index`、`target_index`、`phase`、`status`。只有在日志、任务行和两侧数据共同证明应保留哪个物理索引后，才由操作者使用 Elasticsearch `_aliases` 的单个原子请求把两个别名恢复到同一个明确索引。不能证明时保留 fail-closed 状态并停止写侧恢复；不要删除任一候选索引，不要使用通配符。别名恢复为唯一共同目标后重启应用，让启动恢复逻辑重新核对并解除安全状态。

## Toxiproxy 故障后连接仍被切断

故障测试必须把连接恢复放在 `finally` 中；对应代码为 `proxy.setConnectionCut(false)`。测试被强制终止时 finally 可能来不及执行，此时停止残留测试进程和 Toxiproxy 容器后重跑完整测试。不要让被切断的代理继续服务后续用例。

```powershell
docker ps --filter ancestor=ghcr.io/shopify/toxiproxy:2.12.0
```

Testcontainers 通常会自动回收容器；如仍有残留，先确认它确实属于本实验且没有测试进程正在使用，再通过 Docker Desktop 或明确容器 ID 停止。不要按镜像通配删除其他项目容器。

## 测试结束后出现异步重连或资源泄漏告警

动态容器地址绑定的 Spring 上下文必须先关闭，之后才能停止 Elasticsearch、MySQL 和 Toxiproxy。正常的 Maven `verify` 会让共享测试基类按这个顺序关闭异步 Elasticsearch transport 和应用 executor。

若在容器停止后仍看到 `Connection refused` 重连、线程未退出、JVM 不结束或连接池泄漏：

1. 不要把构建视为验收通过，即使测试断言已完成。
2. 保存 Maven 末尾日志，定位未关闭的 Spring context、Elasticsearch transport、scheduler 或 rebuild executor。
3. 确认没有并行遗留的 `mvnw`/Java 测试进程，再重新执行 `mvnw.cmd verify`。
4. 验收日志必须在容器关闭后保持安静，且 Failsafe XML 为 0 failures、0 errors、0 skipped。

Windows 定向执行集成测试时记得引用整个属性参数：

```powershell
.\mvnw.cmd "-Dit.test=RebuildAndRecoveryDrillIT" verify
```

## 旧索引清理提醒

清理旧物理索引前，必须分别读取 `products-read` 和 `products-write`，确认它们唯一且共同指向当前索引，再删除一个明确写出的、未被引用的旧物理索引名。禁止 `products-*`、`products-v*`、`_all` 或任何通配符删除；详细步骤见 [README.md](README.md#安全清理旧物理索引)。
