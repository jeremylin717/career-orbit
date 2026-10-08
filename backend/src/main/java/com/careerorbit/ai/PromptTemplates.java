package com.careerorbit.ai;

/**
 * 所有提示词集中在此，禁止散落在业务代码中。
 * 每个方法返回一段可直接发给大模型的提示词文本。
 */
public final class PromptTemplates {

    private PromptTemplates() {
    }

    /** 简历原文 -> 固定结构的 JSON（只抽取原文信息，不补充、不推测）。 */
    public static String resumeExtract(String text) {
        return """
                你是严格的简历信息抽取器。只提取原文存在的信息，绝不补充或推测。返回单个 JSON 对象，不要 Markdown。
                JSON 字段：basicInfo{name,email,phone,location},jobIntention,education[],skills[],projects[{name,role,description,technologies,results}],workExperience[{company,role,startDate,endDate,description}],summary。
                缺失字符串用空字符串，缺失数组用空数组。简历原文：
                """ + text;
    }

    /** 简历 JSON -> 诊断与优化建议（只改表达、不改事实）。 */
    public static String resumeDiagnosis(String json) {
        return """
                你是资深简历优化专家。目标是让表达更有冲击力、更专业，但绝不改变事实。
                硬性要求：
                1. 只能优化措辞与结构，不得发明或推断公司、项目、职责、技术栈、数字或结果；原文没有的数据一律不得添加。
                2. rewrite 必须是 original 的实质性改写，禁止与原文几乎相同或原样复制。改写手段：替换更有力的动词（如"负责"→"主导/推动"、"参与"→"深度参与并推动"）、结果导向表述、删除口语与冗余、补充结构化连接词、必要时调整语序以突出重点。
                3. 改写幅度要求：rewrite 与 original 的字面重合度应明显低于 100%，但不得新增任何事实性信息。
                4. 如果某处原文已经足够专业、确实没有可优化空间，就不要为它编造 issue。
                返回单个 JSON，不要 Markdown。
                格式：{overallScore,completenessScore,professionalismScore,expressionScore,issues:[{id,section,severity,original,suggestion,rewrite,reason}]}。
                original 必须逐字取自简历原文，便于系统精确定位。分数为 0-100。简历：
                """ + json;
    }

    /** 简历 + 岗位配置 + RAG 检索知识 -> 个性化题目 JSON 数组。 */
    public static String questions(String resume, String config, String context) {
        return """
                你是技术面试官。基于候选人真实简历、岗位配置和检索知识生成个性化题目。不得虚构候选人经历。仅返回 JSON 数组。
                每项字段：question,type,difficulty,knowledgePoints(数组),sourceChunkIds(只能从检索来源选择),referenceAnswer,scoringRubric,reason。
                简历：%s
                配置：%s
                检索知识：%s
                """.formatted(resume, config, context);
    }

    /** 题目 + 回答 + RAG 参考知识 -> 结构化评分 JSON。 */
    public static String evaluation(String question, String answer, String context) {
        return """
                你是严格面试评分员。依据检索到的参考知识评价回答，只返回 JSON：
                {overallScore,technicalCorrectness,completeness,logic,authenticity,clarity,strengths[],problems[],missingKnowledge[],suggestions[],referenceAnswer,shouldFollowUp,followUpQuestion}。
                所有分数 0-100。追问仅用于澄清关键遗漏或真实性，每题最多由业务层限制两次。
                问题：%s
                回答：%s
                参考知识：%s
                """.formatted(question, answer, context);
    }

    /** 面试问答记录 -> 面试报告 JSON。 */
    public static String report(String transcript) {
        return """
                根据真实面试问答和逐题评价生成报告，只返回 JSON：{overallScore,dimensions{technical,completeness,logic,authenticity,clarity},strengths[],weakPoints[],suggestions[],recommendedQuestions[]}。不得改写逐题分数，综合分应是逐题得分的合理加权结果。数据：
                """ + transcript;
    }
}
