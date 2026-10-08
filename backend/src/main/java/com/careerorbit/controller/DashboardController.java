package com.careerorbit.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.careerorbit.entity.*;
import com.careerorbit.mapper.*;
import com.careerorbit.security.CurrentUser;
import com.careerorbit.common.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 首页工作台：当前用户的简历数、面试数、得分趋势与最近面试。 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(DashboardController.class);

    /** 简历表 Mapper。 */
    private final ResumeMapper resumes;

    /** 面试会话表 Mapper。 */
    private final InterviewSessionMapper sessions;

    /** 面试报告表 Mapper。 */
    private final InterviewReportMapper reports;

    /** 当前登录用户。 */
    private final CurrentUser current;

    public DashboardController(ResumeMapper resumes, InterviewSessionMapper sessions, InterviewReportMapper reports, CurrentUser current) {
        this.resumes = resumes;
        this.sessions = sessions;
        this.reports = reports;
        this.current = current;
    }

    @GetMapping
    ApiResponse<?> dashboard() {
        log.debug("接口调用 GET /api/dashboard");
        long uid = current.require().id;
        var resumeList = resumes.selectList(new QueryWrapper<ResumeEntity>().eq("user_id", uid));
        var interviewList = sessions.selectList(new QueryWrapper<InterviewSessionEntity>().eq("user_id", uid).orderByDesc("started_at").last("LIMIT 5"));
        var reportList = reports.selectList(new QueryWrapper<InterviewReportEntity>().eq("user_id", uid).orderByAsc("created_at"));
        return ApiResponse.ok(new Dashboard(
                resumeList.size(),
                interviewList.size(),
                reportList.stream().map(r -> r.overallScore).toList(),
                interviewList));
    }

    record Dashboard(int resumeCount, int interviewCount, List<Double> scoreTrend, List<InterviewSessionEntity> recentInterviews) { }
}

