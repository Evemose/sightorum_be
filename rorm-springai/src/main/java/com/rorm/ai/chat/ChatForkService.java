package com.rorm.ai.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.ai.chat.node.TrainingQueuedNode;
import com.rorm.ml.dto.TrainingJobResponse;
import com.rorm.ml.dto.TrainingRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@RequiredArgsConstructor
public class ChatForkService {

    private final ChatProgressRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional
    public ChatProgress forkForTraining(
        ChatProgress parentProgress,
        TrainingRequest request,
        TrainingJobResponse response
    ) {
        log.info("Forking chat {} for training {}",
            parentProgress.getConversationId(), response.trainingId());

        var forkedProgress = parentProgress.fork(request.reason(), request.furtherInstructions());

        var trainingQueuedNode = new TrainingQueuedNode(
            response.trainingId(),
            objectMapper.valueToTree(request)
        );
        forkedProgress.addNode(trainingQueuedNode);

        forkedProgress.waitForTraining();

        repository.save(forkedProgress);

        log.info("Created forked chat {} waiting for training {}",
            forkedProgress.getConversationId(), response.trainingId());

        return forkedProgress;
    }
}
