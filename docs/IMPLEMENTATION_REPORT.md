# prompt3 实施与验收报告

更新日期：2026-10-06

## 本轮已实现

- Chat 与 Embedding 完全拆分为独立网关、独立环境变量和独立调用日志，可分别连接 DeepSeek、通义或其他 OpenAI 兼容服务。
- 动态追问把实际题目、`questionType`、`parentAnswerId` 同时保存到 MySQL 与 Redis；刷新可恢复，连续追问按追问文本评分。
- 面试官提问 SSE 直接读取并转发模型服务的 Token，前端用 `fetch + ReadableStream` 消费，处理完成、异常和取消。
- RAG 重建前按文档删除旧 ES 向量；上传、删除、重建失败记录 `FAILED/errorReason`；ES 增加连接与读取超时；上传失败清理跨存储残留。
- 简历原文件改为鉴权下载接口，扩展名、MIME、大小和归属均校验，不再暴露不可访问的 MinIO URL。
- 采纳建议 ID 和新版本来源持久化；支持撤销采纳、版本列表、父子版本和前后 JSON 差异。
- 新增用户、双模型状态、AI 日志、提示词与知识库管理 UI；管理路由校验真实用户角色，普通用户进入 403 页面。
- 新增 `dev` profile、旧库幂等字段升级、PowerShell 前后端/一键启动脚本，以及 README 建库建用户 SQL。
- 报告重复生成改为幂等返回已有报告。

## 自动化验证

- `mvn test`：通过。当前源码 11 个后端测试，覆盖认证、简历越权、题目去重生成、RRF 去重、追问恢复、连续追问、报告幂等、网关独立配置和 integration profile 启动/schema。
- `npm run build`：通过。
- `npm test`：3 个测试通过，覆盖 SSE 跨网络分片解析、SSE 错误事件、管理员路由 403/放行。

## 本机真实启动结果

- MySQL `localhost:3306` 端口可达。
- 使用 README 默认 `career / career_dev_password` 实际启动后端失败，MySQL 返回 `Access denied for user 'career'@'localhost'`。因此凭据已被真实验证为不匹配，而不是把“端口可达”误报为启动成功。
- 本机没有 Docker 命令；Redis 6379、Elasticsearch 9200、MinIO 9000 不可达；没有 Chat/Embedding API Key。

## 尚不能宣称完成的验收

完整“注册 → 登录 → 上传简历 → AI 解析/优化 → 知识入库 → ES 检索 → 生成题目 → 面试/追问 → 报告/历史”E2E 未执行。阻塞项是外部运行环境与凭据，不是用假数据替代。先由 MySQL 管理员执行 README 中的建库授权 SQL，再启动 Redis、Elasticsearch、MinIO并填写两个真实模型密钥，之后才能进行最终 E2E 验收和保存真实请求/响应/数据库证据。
