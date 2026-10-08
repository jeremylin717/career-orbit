package com.careerorbit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.careerorbit.entity.InterviewSessionEntity;
import org.apache.ibatis.annotations.Mapper;

/** 面试会话表 Mapper。 */
@Mapper
public interface InterviewSessionMapper extends BaseMapper<InterviewSessionEntity> {
}
