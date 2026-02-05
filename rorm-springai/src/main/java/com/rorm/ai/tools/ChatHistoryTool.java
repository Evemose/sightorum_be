package com.rorm.ai.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.ai.RormToolContext;
import com.rorm.ai.chat.ChatProgress;
import com.rorm.ai.chat.node.AgentSubconclusionNode;
import com.rorm.ai.chat.node.ChatNodeRepository;
import com.rorm.ai.tools.dto.ChatNodeDetailDTO;
import com.rorm.ai.tools.dto.ChatNodeSummaryDTO;
import com.rorm.ai.tools.mapper.ChatNodeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Tool for navigating and inspecting chat history.
 * Allows AI to explore conversation history by listing nodes
 * and retrieving detailed information about specific nodes.
 */
@Slf4j
@RequiredArgsConstructor
public class ChatHistoryTool {

    private final ChatNodeRepository chatNodeRepository;
    private final ChatMemoryRepository chatMemoryRepository;
    private final ChatNodeMapper chatNodeMapper;
    private final ObjectMapper objectMapper;

    @Tool(
        name = "listChatHistory",
        description = """
            List all chat nodes in the current conversation, tracing from the current context
            back to the oldest ancestor. Returns a linear list of nodes ordered from oldest to newest,
            with each node containing its ID, type, creation timestamp, and a brief summary.
            Use this to understand the conversation flow and find specific nodes for detailed inspection.
            """
    )
    public String listChatHistory(ToolContext toolContext) {
        try {
            log.info("Listing chat history");

            var context = RormToolContext.from(toolContext);
            var chatProgress = context.chatProgress();

            // Collect all nodes from current progress and all parents
            List<ChatNodeSummaryDTO> allNodes = new ArrayList<>();
            collectNodesFromProgressChain(chatProgress, allNodes);

            // Reverse to get oldest first
            Collections.reverse(allNodes);

            var response = new ChatHistoryListResponse(
                true,
                allNodes.size(),
                allNodes,
                null
            );

            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            log.error("Failed to list chat history", e);
            return errorResponse("Failed to list chat history: " + e.getMessage());
        }
    }

    @Tool(
        name = "getChatNodeDetails",
        description = """
            Get detailed information about a specific chat node by its ID.
            Returns the full node data including all properties specific to its type.
            For AgentSubconclusionNode, also fetches the associated conversation memory.
            Use this after listing chat history to inspect specific nodes of interest.
            """
    )
    public String getChatNodeDetails(
        @ToolParam(description = "The UUID of the chat node to retrieve details for")
        String nodeId
    ) {
        try {
            log.info("Getting chat node details for: {}", nodeId);

            var uuid = UUID.fromString(nodeId);
            var nodeOpt = chatNodeRepository.findById(uuid);

            if (nodeOpt.isEmpty()) {
                return errorResponse("Chat node not found: " + nodeId);
            }

            var node = nodeOpt.get();
            ChatNodeDetailDTO detailDTO;

            // Special handling for AgentSubconclusionNode to fetch conversation memory
            if (node instanceof AgentSubconclusionNode subconclusionNode) {
                var conversationMemory = chatMemoryRepository.findByConversationId(
                    subconclusionNode.getConversationId()
                );
                detailDTO = chatNodeMapper.toDetailDTO(subconclusionNode, conversationMemory);
            } else {
                detailDTO = chatNodeMapper.toDetailDTO(node);
            }

            var response = new ChatNodeDetailResponse(
                true,
                detailDTO,
                null
            );

            return objectMapper.writeValueAsString(response);
        } catch (IllegalArgumentException e) {
            log.error("Invalid node ID format: {}", nodeId, e);
            return errorResponse("Invalid node ID format: " + nodeId);
        } catch (Exception e) {
            log.error("Failed to get chat node details", e);
            return errorResponse("Failed to get chat node details: " + e.getMessage());
        }
    }

    private String errorResponse(String message) {
        try {
            return objectMapper.writeValueAsString(
                new ChatHistoryListResponse(false, 0, null, message)
            );
        } catch (JsonProcessingException _) {
            return "{\"success\":false,\"error\":\"" + message.replace("\"", "\\\"") + "\"}";
        }
    }

    private void collectNodesFromProgressChain(ChatProgress progress, List<ChatNodeSummaryDTO> nodes) {
        progress.getParent().ifPresent(parent -> collectNodesFromProgressChain(parent, nodes));
        for (var node : progress.getNodes()) {
            nodes.add(chatNodeMapper.toSummaryDTO(node));
        }
    }

    public record ChatHistoryListResponse(
        boolean success,
        int nodeCount,
        List<ChatNodeSummaryDTO> nodes,
        String error
    ) {}

    public record ChatNodeDetailResponse(
        boolean success,
        ChatNodeDetailDTO node,
        String error
    ) {}
}
