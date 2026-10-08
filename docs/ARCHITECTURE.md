# 系统架构

```text
Vue 3 + Pinia + Axios
        │ JWT / REST / SSE
Spring Boot
  ├─ Security：注册、登录、用户/管理员权限
  ├─ Resume：MinIO → Tika → Spring AI → MySQL
  ├─ RAG：Tika → Chunk → EmbeddingModel → Elasticsearch
  │        BM25 ─┐
  │        Vector├→ RRF → TopK + source/chunkId
  ├─ Questions：Resume + JD + RAG → ChatModel → MySQL
  ├─ Interview：Redis 临时状态 + MySQL 正式问答 + SSE
  └─ Report：真实逐题评价 → ChatModel → MySQL
```

## 数据与边界

- `app_user`：BCrypt 密码摘要与角色。
- `resume` / `resume_diagnosis`：原文件地址、原文、结构化 JSON、诊断与版本链。
- `knowledge_document` / `knowledge_chunk`：知识文档和分块元数据；向量本体位于 Elasticsearch。
- `question_set`：生成配置、问题、参考答案、评分标准与来源 chunkId。
- `interview_session` / `interview_answer`：会话状态、真实问题、回答与模型评价。
- `interview_report`：本次问答产生的报告；综合分由逐题真实分数均值覆盖，防止模型随意改分。
- `ai_call_log`：只记录模型、延迟、成功状态和错误码，不保存简历或回答正文。

## 失败策略

- `AI_CONFIGURED` 不为 `true` 时，Chat 与 Embedding 统一返回“模型未配置”。
- MinIO、Redis、Elasticsearch、MySQL 不可用时操作失败，不回退到内存数据。
- 正常 profile 中不存在 InMemory 用户、知识库或面试仓库。
- 每题追问由模型建议，但业务层强制最多两次。
