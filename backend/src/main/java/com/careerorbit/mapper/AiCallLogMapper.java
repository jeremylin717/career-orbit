package com.careerorbit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.careerorbit.entity.AiCallLogEntity;
import org.apache.ibatis.annotations.Mapper;

/** AI 调用日志表 Mapper。 */
@Mapper
public interface AiCallLogMapper extends BaseMapper<AiCallLogEntity> {
}
