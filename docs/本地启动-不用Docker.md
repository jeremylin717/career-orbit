# 不用 Docker 完整跑通 Career Orbit(Windows 原生方案)

> 目标:不安装 Docker,在 Windows 上把「注册 → 上传简历 → AI 解析/优化 → 知识入库 → ES 检索 → 生成题目 → 面试/追问 → 报告/历史」整条链路真实跑通。

## 一、先搞清楚要准备什么

Docker 只是把下面 4 个服务打包一键启动,不用 Docker 就要自己分别装。这 4 个服务都是代码里真实调用的,不能缺:

| # | 服务 | 作用 | 默认端口 | Windows 获得方式 |
|---|---|---|---|---|
| 1 | MySQL 8 | 用户/简历/题目/报告持久化 | 3306 | 官网 zip 绿色版或安装版 |
| 2 | Redis | 存模拟面试临时进度 | 6379 | Memurai / tporadowski redis-windows / WSL |
| 3 | Elasticsearch 8 | RAG 向量库 + BM25 检索 | 9200 | 官网 zip,自带 JDK |
| 4 | MinIO | 存简历与知识文档原文件 | 9000 / 9001(控制台) | 单个 minio.exe |

另外还需要:

- **JDK 17、Maven 3.9+、Node.js 20+**(脚本已能识别 `C:\Users\pc\.m2` 下的 Maven)
- **两个大模型密钥**(可以来自不同厂商):
  - Chat 模型:DeepSeek(platform.deepseek.com)
  - Embedding 模型:通义千问 / 阿里云百炼 DashScope,模型 `text-embedding-v3`

> 备注:上面 4 个服务的默认端口正好和 `docker-compose.yml` 一致,所以 `.env` 里的连接串默认不用改。

## 二、逐个把服务跑起来

### 1. MySQL 8

用安装版时记下 root 密码;用 zip 绿色版时,解压后初始化并用 `mysqld` 启动。启动后用 **MySQL 管理员**执行(来自 README):

```sql
CREATE DATABASE IF NOT EXISTS career_orbit
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'career'@'localhost'
  IDENTIFIED BY 'career_dev_password';
GRANT ALL PRIVILEGES ON career_orbit.* TO 'career'@'localhost';
FLUSH PRIVILEGES;
```

启动后自检(应能连上并返回 1):

```powershell
Test-NetConnection -ComputerName localhost -Port 3306
```

表结构不用手动建:后端启动时 `schema.sql` 会自动执行(`CREATE TABLE IF NOT EXISTS`),旧库则由 `SchemaUpgrade` 幂等补列。

### 2. Redis

官方最新 Redis 不再提供 Windows 原生版,三选一:

- **Memurai**(商业,免费开发版,最省事):装完作为服务自动运行在 6379
- **tporadowski/redis**(社区维护的 Redis 5.0.14 Windows 版,绿色):解压后 `redis-server.exe redis.windows.conf`
- **WSL2**:`sudo apt install redis-server` 后 `redis-server`

自检:

```powershell
Test-NetConnection -ComputerName localhost -Port 6379
```

### 3. Elasticsearch 8

下载 ES 8.x 的 zip 解压(自带 JDK,不用额外装 Java)。

> **下载慢/超时**:官方源 `artifacts.elastic.co` 在国内常很慢。华为云镜像有同步,但**最新只到 8.9.2**(没有 8.19.5),8.9.2 对本项目完全够用:
>
> ```powershell
> Invoke-WebRequest -Uri "https://mirrors.huaweicloud.com/elasticsearch/8.9.2/elasticsearch-8.9.2-windows-x86_64.zip" -OutFile "elasticsearch-8.9.2-windows-x86_64.zip" -UseBasicParsing
> ```
>
> 或直接跑 `.\scripts\install-infra.ps1`(默认用 8.9.2 华为镜像,并自动回退官方源)。

> **建议装到 F 盘而非 C 盘**:ES 有磁盘保护——数据所在盘用量超过 **90%** 时,会拒绝分配分片,集群变 `red`,表现为后端报 `Elasticsearch 请求失败: request timed out`。项目脚本默认安装目录已改为 `F:\career-orbit-infra`。若之前装到了 C 盘,可用 `.\scripts\move-infra-to-f.ps1` 整个迁到 F 盘(会先停掉 ES/MinIO 再移动,ES 数据与 MinIO 文件一并迁移)。

**关键一步**:ES 8 默认开启安全认证,必须关掉,否则后端连不上——打开 `config\elasticsearch.yml`,加入:

```yaml
xpack.security.enabled: false
discovery.type: single-node
```

内存不够的话,编辑 `config\jvm.options`,设 `-Xms512m -Xmx512m`(和 docker-compose 里一致)。

用 PowerShell 启动:

```powershell
cd <ES目录>
.\bin\elasticsearch.bat
```

自检(应返回 JSON,含 version 8.x):

```powershell
Invoke-RestMethod http://localhost:9200
```

> 索引与分块向量都不用手动建:后端首次入库时会自动创建索引,向量维度是运行时探测的。

### 4. MinIO

下载单个 `minio.exe`,在数据目录里启动(控制台走 9001):

```powershell
.\minio.exe server C:\minio-data --console-address ":9001"
```

> **下载源注意(2026 年变故)**:MinIO 已转向闭源 AIStor,原官方直链 `https://dl.min.io/server/minio/release/windows-amd64/minio.exe` 现返回 **HTTP 410 Gone**,GitHub 仓库也已归档。可改用官方中国加速镜像(**只需把 `dl.min.io` 换成 `dl.minio.org.cn`**):
>
> ```powershell
> Invoke-WebRequest -Uri "https://dl.minio.org.cn/server/minio/release/windows-amd64/minio.exe" -OutFile "C:\minio\minio.exe" -UseBasicParsing
> ```
>
> 或运行项目脚本 `.\scripts\install-infra.ps1`,它会自动按「中国镜像 → 旧官方 → AIStor」顺序重试。

默认账号 `minioadmin` / `minioadmin`,与项目配置一致。**bucket 不用手动建**:代码里 `ObjectStorageService.put` 会检查并在不存在时自动创建 `career-orbit`。

自检:

```powershell
Test-NetConnection -ComputerName localhost -Port 9000
```

## 三、配置 .env

在项目根目录 `career-orbit\` 下:

```powershell
Copy-Item .env.example .env
```

然后编辑 `.env`,重点填这几项:

```dotenv
# 换成至少 32 位随机串
JWT_SECRET=请换成足够长的随机字符串

# Chat 模型(DeepSeek)
CHAT_BASE_URL=https://api.deepseek.com
CHAT_API_KEY=你的deepseek密钥
CHAT_MODEL=deepseek-chat
CHAT_CONFIGURED=true

# Embedding 模型(通义 / DashScope,可与 Chat 不同厂商)
EMBEDDING_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode
EMBEDDING_API_KEY=你的通义密钥
EMBEDDING_MODEL=text-embedding-v3
EMBEDDING_CONFIGURED=true

# 首次启动会据此创建管理员
ADMIN_EMAIL=admin@career.local
ADMIN_PASSWORD=请设一个强密码
```

如果你连 MySQL 用的是 root 而不是 `career` 账号,把 `DB_USER` / `DB_PASSWORD` 改成实际值即可。

## 四、按顺序启动

1. 先起 4 个基础设施服务:MySQL、Redis、Elasticsearch、MinIO
2. 确认端口都在:`3306`、`6379`、`9200`、`9000`
3. 起后端(脚本会读取 `.env`,默认连 localhost 的 MySQL):

```powershell
.\scripts\start-backend.ps1
```

首次启动若 MySQL 账号还没建好,可临时跳过后端脚本里的连通性检查:

```powershell
.\scripts\start-backend.ps1 -SkipChecks
```

4. 再起前端:

```powershell
.\scripts\start-frontend.ps1
```

访问入口:

- Web:`http://localhost:5173`
- API 文档(Swagger):`http://localhost:8080/swagger-ui.html`
- MinIO 控制台:`http://localhost:9001`
- Elasticsearch:`http://localhost:9200`

## 五、验收顺序(真实链路)

1. 打开 `http://localhost:5173`,注册一个普通用户并登录
2. 用 `.env` 里的管理员账号登录,进入「管理控制台 → RAG 知识库」,上传一份面试题/技术文档(PDF/Word/TXT/MD),确认变 `READY`,能看到 chunk 统计
3. 回到普通用户,「我的简历」上传 PDF/Word 简历,确认解析出结构化 JSON
4. 「简历优化」触发诊断,采纳一条建议,查看版本差异
5. 「面试题生成」按岗位方向生成题目(这一步会先检索知识库,知识库为空会直接报错)
6. 「模拟面试」开始 → 作答 → 观察是否触发追问(每题最多追问 2 次)→ 跳过/结束
7. 「面试报告」查看逐题分析、能力维度、学习建议
8. 「面试记录」/首页查看历史与趋势

## 六、本机已经有 MySQL / Redis 的情况

如果这两样电脑上已有,可以跳过指南里的安装步骤,只需验证能被项目连上:

```powershell
# MySQL 是否在 3306
Test-NetConnection -ComputerName localhost -Port 3306
# 看版本(会提示输密码),推荐 8.x
mysql -u root -p -e "SELECT VERSION();"
# Redis 是否在 6379,正常返回 PONG
redis-cli ping
```

然后用 MySQL 管理员账号执行建库建用户 SQL(见第二节 1),再把 `.env` 里的 `DB_USER`/`DB_PASSWORD` 和 `REDIS_HOST`/`REDIS_PORT` 对齐本机实际值。这样你只需要再装 **Elasticsearch** 和 **MinIO** 两个服务即可。

> **直接用 root 账号更省事**:如果 `.env` 里 `DB_USER=root` 且连接串带 `createDatabaseIfNotExist=true`(本项目 `.env` 已如此配置),则连建库 SQL 都不用执行——root 有建库权限,后端首次启动会自动创建 `career_orbit` 库并执行 `schema.sql` 建表。只有在你希望用受限的 `career` 账号时才需要跑第二节的建库建用户 SQL。

## 七、常见坑

- **ES 连不上**:90% 是忘了在 `elasticsearch.yml` 里关 `xpack.security.enabled`,或 9200 端口没起。
- **上传知识库报「没有可解析文字」**:扫描版 PDF 无文字层,Tika 抽不出文字,换可复制文本的文件。
- **AI 流程报「未配置」**:`.env` 里 `CHAT_CONFIGURED` / `EMBEDDING_CONFIGURED` 没设成 true,或密钥为空。
- **面试功能报「Redis 不可用」**:Redis 没起;模拟面试的临时进度强依赖 Redis。
- **后端启动即失败并提示 MySQL 凭据无效**:README 里的建库建用户 SQL 还没执行。
- **ES 8 内存占用高**:调小 `config\jvm.options` 的堆,单机 512m 够用。
- **端口冲突**:若本机 3306/6379/9200/9000 已被别的服务占用,需改对应服务端口并同步改 `.env`。

## 八、更省事的替代:WSL2

如果嫌 Windows 原生装 ES、Redis 麻烦,可以用 WSL2(Ubuntu)——它不算 Docker,但比 Windows 原生顺:

```bash
sudo apt update
sudo apt install redis-server
# ES 用官方 tar 包解压启动(xpack.security.enabled: false)
```

WSL2 与 Windows 共享 localhost 端口,后端照常连 `localhost:9200/6379` 即可。MinIO 仍建议直接跑 Windows 版 `minio.exe`。
