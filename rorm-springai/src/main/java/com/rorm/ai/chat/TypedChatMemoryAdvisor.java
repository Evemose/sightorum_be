package com.rorm.ai.chat;

import com.rorm.StepJournal;
import com.rorm.ai.anthropic.ServerToolGeneration;
import com.rorm.ai.anthropic.ThinkingGeneration;
import com.rorm.ai.anthropic.ToolRoundGeneration;
import lombok.Builder;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.*;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

@Builder
public class TypedChatMemoryAdvisor implements CallAdvisor, StreamAdvisor {

    private final ChatMemoryManager memoryManager;
    private final String conversationId;
    @Builder.Default
    private final Set<MemoryInclude> includes = Set.of();
    @Builder.Default
    private final StepJournal journal = StepJournal.current();
    @Builder.Default
    private final int order = Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER;

    @Override
    public String getName() {
        return "TypedChatMemoryAdvisor";
    }

    @Override
    public int getOrder() {
        return order;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        var modifiedRequest = before(request);
        var response = chain.nextCall(modifiedRequest);

        if (response.chatResponse() != null) {
            var userMsg = modifiedRequest.prompt().getUserMessage();
            memoryManager.saveMessages(
                conversationId, userMsg,
                response.chatResponse().getResults(), journal
            );
        }
        return response;
    }

    private ChatClientRequest before(ChatClientRequest request) {
        var history = memoryManager.loadHistory(conversationId, includes);
        var messages = new ArrayList<>(history);
        messages.addAll(request.prompt().getInstructions());
        ensureSystemFirst(messages);
        return request.mutate()
            .prompt(request.prompt().mutate().messages(messages).build())
            .build();
    }

    private static void ensureSystemFirst(List<Message> messages) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof SystemMessage) {
                var systemMsg = messages.remove(i);
                messages.addFirst(systemMsg);
                return;
            }
        }
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        var modifiedRequest = before(request);
        var customGenerations = new CopyOnWriteArrayList<Generation>();

        return chain.nextStream(modifiedRequest)
            .doOnNext(resp -> {
                if (resp.chatResponse() == null) {
                    return;
                }
                var results = resp.chatResponse().getResults();
                // Only the final ChatResponse from emitCustomGenerations carries
                // multiple generations. Streaming chunks are single-generation
                // visualization events (ThinkingGeneration per token, text chunks,
                // StreamToolCallGeneration) and must not be persisted to memory.
                if (results.size() <= 1) {
                    return;
                }
                for (var gen : results) {
                    if (gen instanceof ThinkingGeneration
                        || gen instanceof ToolRoundGeneration
                        || gen instanceof ServerToolGeneration) {
                        customGenerations.add(gen);
                    }
                }
            })
            .transform(flux -> new ChatClientMessageAggregator().aggregateChatClientResponse(
                flux,
                aggregatedResponse -> {
                    var allGenerations = new ArrayList<Generation>();
                    if (aggregatedResponse.chatResponse() != null) {
                        allGenerations.addAll(aggregatedResponse.chatResponse().getResults());
                    }
                    allGenerations.addAll(customGenerations);

                    var userMsg = modifiedRequest.prompt().getUserMessage();
                    memoryManager.saveMessages(conversationId, userMsg, allGenerations, journal);
                }
            ));
    }
}
