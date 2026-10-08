package com.careerorbit.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.careerorbit.ai.ChatGateway;
import com.careerorbit.ai.PromptTemplates;
import com.careerorbit.entity.*;
import com.careerorbit.mapper.*;
import com.careerorbit.security.CurrentUser;
import com.careerorbit.common.BusinessException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 模拟面试服务：一个由状态机驱动的面试流程。
 *
 * <p>状态机：题目分两层——{@code BASE}（主问题）与 {@code FOLLOW_UP}（基于回答质量的动态追问）。
 * 每答完一题，模型决定是否追问；追问受 {@link #MAX_FOLLOW_UPS} 限制，超过就进入下一道主问题。</p>
 *
 * <p>存储策略：会话进度临时存 Redis（刷新页面可恢复），正式问答与报告落 MySQL。</p>
 *
 * <p>事务策略：{@link #answer}、{@link #complete} 含大模型调用，故不用方法级 {@code @Transactional}，
 * 而是用 {@link TransactionTemplate} 只把落库步骤包进事务，避免慢调用长时间占用数据库连接。</p>
 */
@Service
public class InterviewService {

    /** 日志。注意：绝不记录用户回答原文。 */
    private static final Logger log = LoggerFactory.getLogger(InterviewService.class);

    /** 题目类型：主问题。 */
    private static final String BASE = "BASE";

    /** 题目类型：追问。 */
    private static final String FOLLOW_UP = "FOLLOW_UP";

    /** 每道主问题最多追问次数。 */
    private static final int MAX_FOLLOW_UPS = 2;

    /** 面试会话 Mapper（MySQL）。 */
    private final InterviewSessionMapper sessions;

    /** 问答记录 Mapper。 */
    private final InterviewAnswerMapper answers;

    /** 报告 Mapper。 */
    private final InterviewReportMapper reports;

    /** 题目集服务（拿题目）。 */
    private final QuestionGenerationService questionSets;

    /** RAG 服务（检索参考知识）。 */
    private final RagService rag;

    /** 聊天网关（评价回答）。 */
    private final ChatGateway chat;

    /** 当前登录用户。 */
    private final CurrentUser current;

    /** Redis（存会话进度）。 */
    private final StringRedisTemplate redis;

    /** JSON 工具。 */
    private final ObjectMapper json;

    /** 事务模板：只把落库步骤包进事务。 */
    private final TransactionTemplate tx;

    public InterviewService(InterviewSessionMapper sessions, InterviewAnswerMapper answers, InterviewReportMapper reports, QuestionGenerationService questionSets, RagService rag, ChatGateway chat, CurrentUser current, StringRedisTemplate redis, ObjectMapper json, TransactionTemplate tx) {
        this.sessions = sessions;
        this.answers = answers;
        this.reports = reports;
        this.questionSets = questionSets;
        this.rag = rag;
        this.chat = chat;
        this.current = current;
        this.redis = redis;
        this.json = json;
        this.tx = tx;
    }

    /**
     * 用一份题目集开启面试：初始化会话，指向第一道主问题，并写入 Redis。
     *
     * @param setId 题目集 id
     * @return 会话视图
     */
    @Transactional
    public SessionView create(long setId) {
        log.info("创建面试: questionSetId={}", setId);
        try {
            var set = questionSets.get(setId);
            JsonNode questions = json.readTree(set.questionsJson);   // 题目数组
            if (questions.isEmpty()) throw new BusinessException("题目集为空");
            var s = new InterviewSessionEntity();
            s.id = UUID.randomUUID().toString();          // 会话 id
            s.userId = current.require().id;
            s.targetRole = set.jobTitle;
            s.questionSetId = set.id;
            s.status = "IN_PROGRESS";
            s.currentQuestion = 0;                        // 从第 0 题开始
            s.currentQuestionText = questions.get(0).path("question").asText();
            s.questionType = BASE;
            s.followUpCount = 0;
            s.startedAt = LocalDateTime.now();
            sessions.insert(s);
            cache(s);                                     // 写 Redis
            log.info("面试创建成功: sessionId={}, userId={}, 题目数={}", s.id, s.userId, questions.size());
            return view(s);
        } catch (BusinessException e) {
            log.warn("面试创建失败: questionSetId={}, 原因={}", setId, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("面试创建失败: questionSetId={}", setId, e);
            throw new BusinessException("面试创建失败：" + e.getMessage());
        }
    }

    /** 按 id 取会话视图。 */
    public SessionView get(String id) {
        return view(require(id));
    }

    /**
     * 提交一次回答：先 RAG 检索参考知识，再让模型评分；
     * 根据 shouldFollowUp 与追问次数决定进入追问还是下一道主问题。
     * 模型调用在事务外，仅“插入回答 + 更新会话”在一个事务内。
     *
     * @param id     会话 id
     * @param answer 用户回答
     * @return 本次评价与下一题信息
     */
    public AnswerResult answer(String id, String answer) {
        log.info("提交回答: sessionId={}, 回答长度={}", id, answer == null ? 0 : answer.length());
        try {
            var s = require(id);
            if (!"IN_PROGRESS".equals(s.status)) throw new BusinessException("面试已结束");
            JsonNode questions = questions(s);
            if (s.currentQuestion >= questions.size() || s.currentQuestionText == null || s.currentQuestionText.isBlank()) {
                throw new BusinessException("已没有待回答问题");
            }
            final String actualQuestion = s.currentQuestionText;   // 当前实际被回答的题（主问题或追问）

            // 检索失败不阻断评价：拿不到参考知识就按“无来源”评分
            List<RagService.RagHit> refs;
            try {
                refs = rag.search(actualQuestion + " " + answer, 6);
            } catch (BusinessException ex) {
                log.warn("面试评价前检索失败，降级为无来源评分: sessionId={}, 原因={}", id, ex.getMessage());
                refs = List.of();
            }

            // 模型评价（事务外，可能耗时数秒）
            final JsonNode evaluation = chat.json(
                    PromptTemplates.evaluation(actualQuestion, answer, json.writeValueAsString(refs)),
                    JsonNode.class);

            // 落库（事务内）
            final List<RagService.RagHit> citations = refs;
            var result = tx.execute(status -> {
                var a = new InterviewAnswerEntity();
                a.sessionId = id;
                a.questionText = actualQuestion;
                a.questionType = Objects.requireNonNullElse(s.questionType, BASE);
                a.parentAnswerId = s.parentAnswerId;
                a.answerText = answer;
                a.evaluationJson = evaluation.toString();
                a.score = evaluation.path("overallScore").asDouble();
                a.createdAt = LocalDateTime.now();
                answers.insert(a);

                // 是否追问：模型要求追问 且 未超次数 且 追问问题非空
                boolean follow = evaluation.path("shouldFollowUp").asBoolean(false)
                        && s.followUpCount < MAX_FOLLOW_UPS
                        && !evaluation.path("followUpQuestion").asText().isBlank();
                if (follow) {
                    s.followUpCount++;
                    s.currentQuestionText = evaluation.path("followUpQuestion").asText();
                    s.questionType = FOLLOW_UP;
                    s.parentAnswerId = a.id;              // 追问挂在本条回答下
                } else {
                    advanceToNextBase(s, questions);      // 否则推进到下一道主问题
                }

                // 把最近评价与引用打包成快照，便于刷新恢复
                var snapshot = json.createObjectNode();
                snapshot.set("lastEvaluation", evaluation);
                snapshot.set("citations", json.valueToTree(citations));
                snapshot.put("currentQuestion", Objects.toString(s.currentQuestionText, ""));
                snapshot.put("questionType", s.questionType);
                s.snapshotJson = snapshot.toString();
                sessions.updateById(s);

                return new AnswerResult(a.id, a.score, evaluation, follow, s.currentQuestionText,
                        citations, s.currentQuestion, s.followUpCount, s.questionType, a.parentAnswerId);
            });
            cache(s);                                     // 事务提交后再写 Redis
            log.info("回答评价完成: sessionId={}, score={}, followUp={}, 当前题={}", id, result.score(), result.followUp(), result.questionIndex());
            return result;
        } catch (BusinessException e) {
            log.warn("提交回答失败: sessionId={}, 原因={}", id, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("提交回答失败: sessionId={}", id, e);
            throw new BusinessException("回答评价失败：" + e.getMessage());
        }
    }

    /** 跳过当前题，直接进入下一道主问题（纯数据库操作，保持方法级事务）。 */
    @Transactional
    public SessionView skip(String id) {
        log.info("跳过当前题: sessionId={}", id);
        try {
            var s = require(id);
            advanceToNextBase(s, questions(s));
            sessions.updateById(s);
            cache(s);
            return view(s);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("跳过题目失败: sessionId={}", id, e);
            throw new BusinessException("跳过题目失败：" + e.getMessage());
        }
    }

    /**
     * 结束面试并生成报告。重复调用幂等——已存在报告则直接返回。
     * 模型调用在事务外，仅“插入报告 + 更新会话状态”在事务内。
     *
     * @param id 会话 id
     * @return 报告实体
     */
    public InterviewReportEntity complete(String id) {
        log.info("结束面试并生成报告: sessionId={}", id);
        try {
            var s = require(id);
            var existing = reports.selectOne(new QueryWrapper<InterviewReportEntity>().eq("session_id", id));
            if (existing != null) {
                log.info("报告已存在，直接返回: sessionId={}", id);
                return existing;                          // 幂等
            }
            var list = answers.selectList(new QueryWrapper<InterviewAnswerEntity>().eq("session_id", id).orderByAsc("created_at"));
            if (list.isEmpty()) throw new BusinessException("至少回答一道题后才能生成报告");

            // 模型生成报告（事务外）
            final JsonNode generated = chat.json(PromptTemplates.report(json.writeValueAsString(list)), JsonNode.class);
            final double average = list.stream().mapToDouble(a -> a.score).average().orElse(0);   // 逐题均分
            ((ObjectNode) generated).put("overallScore", Math.round(average * 10) / 10d);   // 综合分强制用均分
            ((ObjectNode) generated).set("questionDetails", json.valueToTree(list));        // 附带逐题明细

            // 落库（事务内）
            var report = tx.execute(status -> {
                var r = new InterviewReportEntity();
                r.sessionId = id;
                r.userId = s.userId;
                r.overallScore = average;
                r.reportJson = generated.toString();
                r.createdAt = LocalDateTime.now();
                reports.insert(r);
                s.status = "COMPLETED";
                s.endedAt = LocalDateTime.now();
                sessions.updateById(s);
                return r;
            });
            redis.delete(key(id));                        // 会话结束，清掉临时进度
            log.info("报告生成成功: sessionId={}, reportId={}, 综合分={}", id, report.id, report.overallScore);
            return report;
        } catch (BusinessException e) {
            log.warn("报告生成失败: sessionId={}, 原因={}", id, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("报告生成失败: sessionId={}", id, e);
            throw new BusinessException("报告生成失败：" + e.getMessage());
        }
    }

    /** 当前用户的历史面试（倒序）。 */
    public List<InterviewSessionEntity> history() {
        return sessions.selectList(new QueryWrapper<InterviewSessionEntity>().eq("user_id", current.require().id).orderByDesc("started_at"));
    }

    /** 取某场面试的报告；未生成则报错。 */
    public InterviewReportEntity report(String id) {
        require(id);
        var r = reports.selectOne(new QueryWrapper<InterviewReportEntity>().eq("session_id", id));
        if (r == null) {
            log.warn("报告尚未生成: sessionId={}", id);
            throw new BusinessException("报告尚未生成");
        }
        return r;
    }

    /**
     * 取会话：优先读 Redis（刷新可恢复），没有则回退 MySQL；并校验归属，防止越权访问他人会话。
     *
     * @param id 会话 id
     * @return 会话实体
     */
    private InterviewSessionEntity require(String id) {
        InterviewSessionEntity s = null;
        try {
            String cached = redis.opsForValue().get(key(id));
            if (cached != null) s = json.readValue(cached, InterviewSessionEntity.class);
        } catch (Exception ignored) {
        }
        if (s == null) s = sessions.selectById(id);
        if (s == null || !s.userId.equals(current.require().id)) throw new BusinessException("面试会话不存在");
        return s;
    }

    /** 取该会话对应的题目数组。 */
    private JsonNode questions(InterviewSessionEntity s) throws Exception {
        return json.readTree(questionSets.get(s.questionSetId).questionsJson);
    }

    /** 组装会话视图（含总题数等派生信息）。 */
    private SessionView view(InterviewSessionEntity s) {
        try {
            JsonNode qs = questions(s);
            return new SessionView(s.id, s.targetRole, s.status, s.currentQuestion, s.followUpCount,
                    qs.size(), s.currentQuestionText, s.questionType, s.parentAnswerId, s.startedAt);
        } catch (Exception e) {
            throw new BusinessException("面试会话数据损坏");
        }
    }

    /** 推进到下一道主问题，并重置追问状态。 */
    private void advanceToNextBase(InterviewSessionEntity s, JsonNode questions) {
        s.currentQuestion++;
        s.followUpCount = 0;
        s.questionType = BASE;
        s.parentAnswerId = null;
        s.currentQuestionText = s.currentQuestion < questions.size()
                ? questions.get(s.currentQuestion).path("question").asText()
                : null;                                   // 题目用完则为 null
    }

    /** 写 Redis 缓存，2 小时过期。 */
    private void cache(InterviewSessionEntity s) {
        try {
            redis.opsForValue().set(key(s.id), json.writeValueAsString(s), 2, TimeUnit.HOURS);
        } catch (Exception e) {
            throw new BusinessException("Redis 不可用，无法保存面试进度");
        }
    }

    /** Redis key 规则。 */
    private String key(String id) {
        return "career:interview:" + id;
    }

    /**
     * 会话视图。
     *
     * @param sessionId       会话 id
     * @param targetRole      目标岗位
     * @param status          状态
     * @param currentQuestion 当前第几题（下标）
     * @param followUpCount   已追问次数
     * @param totalQuestions  总题数
     * @param question        当前待答题目
     * @param questionType    题目类型 BASE/FOLLOW_UP
     * @param parentAnswerId  追随时指向的父回答 id
     * @param startedAt       开始时间
     */
    public record SessionView(String sessionId, String targetRole, String status, int currentQuestion,
                              int followUpCount, int totalQuestions, String question, String questionType,
                              Long parentAnswerId, LocalDateTime startedAt) { }

    /**
     * 一次回答的结果。
     *
     * @param answerId      本条回答 id
     * @param score         本题得分
     * @param evaluation    模型评价 JSON
     * @param followUp      是否进入追问
     * @param nextQuestion  下一题
     * @param citations     引用来源
     * @param questionIndex 当前题下标
     * @param followUpCount 已追问次数
     * @param questionType  下一题类型
     * @param parentAnswerId 父回答 id
     */
    public record AnswerResult(long answerId, double score, JsonNode evaluation, boolean followUp,
                               String nextQuestion, List<RagService.RagHit> citations, int questionIndex,
                               int followUpCount, String questionType, Long parentAnswerId) { }
}
