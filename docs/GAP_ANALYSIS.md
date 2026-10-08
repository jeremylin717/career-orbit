# Career Orbit 初始现状审计与差距分析

> 本文保留的是改造前基线，用于说明原型问题，不代表当前实现。当前完成度和实测阻塞项以 `IMPLEMENTATION_REPORT.md` 为准。

审计日期：2026-10-06  
审计范围：`frontend/src`、`backend/src`、`docker-compose.yml`、数据库脚本与构建产物。

## 1. 硬编码页面与数据

| 页面 | 硬编码内容 | 影响 |
|---|---|---|
| `DashboardView.vue` | 固定用户名、准备度、简历分数、任务、面试记录、能力值 | 无法反映登录用户与真实业务数据 |
| `ResumeView.vue` | 固定简历文件、公司、经历、诊断建议和分数 | 上传、诊断、采纳均未形成持久化闭环 |
| `MatchView.vue` | 固定 JD、技能清单、匹配结果 | “分析”只切换预设结果 |
| `InterviewView.vue` | 固定题目、固定知识来源、固定结束分数 | 不是模型生成或真实评价 |
| `ReportView.vue` | 固定得分、趋势、能力值、问题列表 | 报告与问答记录无关 |
| `App.vue` | 固定用户“林知远”及固定冲刺进度 | 无登录态和用户资料来源 |

## 2. 无真实功能或仅视觉反馈的按钮

- 全局搜索、通知、个人菜单无业务处理。
- 简历“上传”不提交文件；“保存版本”只显示成功消息；预览和版本记录来自静态变量。
- JD “智能匹配”使用 `setTimeout`，没有调用后端。
- 面试作答使用 `setTimeout` 拼接固定回复；语音、编辑设置、来源详情无接口。
- 报告导出、重新练习、专项训练、完整计划无后端操作。
- 首页任务、历史记录均为静态跳转。

## 3. 未调用后端的前端页面

当前 `frontend` 没有 Axios、Pinia、API Client、认证 Store 或请求拦截器。五个业务页面均未调用任何后端接口，也没有登录/注册页面、401/403 处理、加载/空/错误/重试状态。

## 4. 规则或伪实现的后端接口

- `JobMatchService`：用固定技能权重和字符串包含关系评分。
- `InterviewService`：使用固定 `BASE` 列表生成问题；通过回答长度、数字和关键词评分。
- `KnowledgeRetrievalService`：只在 5 条内存数据上做关键词包含计分，不是向量/BM25 混合检索。
- `DashboardController`：直接返回固定分数、趋势和任务。
- `ResumeParsingService`：Tika 提取是真实的，但仅返回预览，不保存文件、文本、结构化结果或版本。
- 所有 AI 配置仅存在于 YAML，项目没有 Spring AI 依赖，也未调用模型。

## 5. 未真正使用的数据库表

`app_user`、`resume`、`job_description`、`knowledge_document`、`knowledge_chunk`、`interview_session`、`interview_answer`、`ai_call_log` 只存在于 `schema.sql`，项目没有实体、Mapper 或 SQL 读写。注册用户和会话在进程重启后全部丢失。

## 6. 基础设施真实接入情况

| 能力 | 当前状态 | 结论 |
|---|---|---|
| Spring AI | 无依赖、无 ChatModel/EmbeddingModel | 未接入 |
| Redis | 有 starter 和配置，但业务使用内存 Map | 未接入业务 |
| Elasticsearch | 只有 Compose 服务 | 未接入 |
| MinIO | 有 SDK 和 Compose 服务，但没有存储代码 | 未接入 |
| MySQL/MyBatis-Plus | 有依赖与 DDL，无 Mapper | 未接入业务 |
| SSE | 固定字符串按标点拆分后流式发送 | 传输是真实 SSE，内容不是模型流 |
| JWT | JWT 签发与校验真实；用户来源为内存仓库 | 部分接入 |

## 7. 与目标流程的差距

当前只能展示 UI 和调用少量内存 API，无法完成“注册登录 → 上传简历 → 模型解析/优化 → 知识入库 → RAG 生成问题 → 模型评价/追问 → 真实报告 → 历史记录”。主要阻断点依次是：

1. 没有持久化身份与业务实体。
2. 没有 MinIO 文件存储。
3. 没有统一 Spring AI 模型网关和未配置保护。
4. 没有 Elasticsearch 向量索引、BM25、RRF 与来源追踪。
5. 没有题目集、正式问答、报告实体。
6. 前端没有认证和 API 状态管理。
7. 存在误生成的 `.vue.js`、`router.js`、`main.js`、`vite.config.js/.d.ts` 和 `*.tsbuildinfo`。

## 8. 改造原则

- 正常 profile 只允许真实 MySQL、Redis、MinIO、Elasticsearch 与模型调用；依赖或密钥缺失时返回明确错误，不返回假数据。
- `mock` 只允许用于自动化测试，且响应必须标记演示模式。
- AI 输出统一使用 JSON Schema/结构化 DTO 校验，解析失败视为调用失败。
- 简历原文与回答正文不得进入 AI 调用日志。
- 所有用户端业务数据由 API 获取，页面必须提供加载、空、错误和重试状态。
