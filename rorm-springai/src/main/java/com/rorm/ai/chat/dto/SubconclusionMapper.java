package com.rorm.ai.chat.dto;

import com.rorm.ai.chat.node.AgentSubconclusionNode;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface SubconclusionMapper {

    AgentSubconclusionNode toEntity(SubconclusionDTO dto, String conversationId);

}
