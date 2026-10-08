package com.careerorbit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.careerorbit.entity.KnowledgeDocumentEntity;
import org.apache.ibatis.annotations.Mapper;

/** 知识库文档表 Mapper。 */
@Mapper
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocumentEntity> {
}
