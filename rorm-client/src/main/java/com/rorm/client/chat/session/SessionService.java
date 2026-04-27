package com.rorm.client.chat.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.ai.swarm.SwarmStreamEvent;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    public static final String SESSION_PREFIX = "session-";
    private static final TypeReference<List<SwarmStreamEvent>> EVENT_LIST_TYPE =
        new TypeReference<>() {};

    private final SessionRepository sessionRepository;
    private final SessionAnalysisRepository analysisRepository;
    private final SessionImportRepository importRepository;
    private final ObjectMapper objectMapper;

    public static String newId() {
        return SESSION_PREFIX + UUID.randomUUID();
    }

    public static boolean isSessionId(@Nullable String id) {
        return id != null && id.startsWith(SESSION_PREFIX);
    }

    /**
     * Returns the existing session, or creates one if the given id does not
     * resolve. The primary chat for a new session shares the session id as its
     * chatId — that's the user-facing chat; internal chats use their own ids.
     */
    @Transactional
    public Session findOrCreate(String sessionId, @Nullable String schemaName) {
        return sessionRepository.findById(sessionId).orElseGet(() ->
            sessionRepository.save(new Session(sessionId, sessionId, schemaName)));
    }

    @Transactional(readOnly = true)
    public List<Session> list() {
        return sessionRepository.findAllMostRecent();
    }

    @Transactional(readOnly = true)
    public Session get(String sessionId) {
        return sessionRepository.findById(sessionId)
            .orElseThrow(() -> new EntityNotFoundException("Session not found: " + sessionId));
    }

    @Transactional
    public void delete(String sessionId) {
        sessionRepository.deleteById(sessionId);
    }

    @Transactional
    public SessionAnalysis registerAnalysis(String sessionId, String runId,
                                            AnalysisKind kind, @Nullable String query) {
        touch(sessionId);
        return analysisRepository.save(new SessionAnalysis(runId, sessionId, kind, query));
    }

    @Transactional
    public void markAnalysisSucceeded(String runId, @Nullable List<SwarmStreamEvent> events) {
        analysisRepository.findById(runId).ifPresent(a -> {
            a.markSucceeded(toTree(events));
            analysisRepository.save(a);
            touch(a.getSessionId());
        });
    }

    /**
     * Converts a typed event list to a JsonNode tree, going through a writer
     * that knows the static type. Without the explicit TypeReference, type
     * erasure on the parameterized list would cause Jackson to serialize each
     * element by its concrete record class (which carries no
     * {@code @JsonTypeInfo}) and the polymorphic discriminator would be lost.
     */
    @SneakyThrows
    private @Nullable JsonNode toTree(@Nullable List<SwarmStreamEvent> events) {
        if (events == null) {
            return null;
        }
        var json = objectMapper.writerFor(EVENT_LIST_TYPE).writeValueAsString(events);
        return objectMapper.readTree(json);
    }

    @Transactional
    public void markAnalysisFailed(String runId, String errorMessage,
                                   @Nullable List<SwarmStreamEvent> events) {
        analysisRepository.findById(runId).ifPresent(a -> {
            a.markFailed(errorMessage, toTree(events));
            analysisRepository.save(a);
            touch(a.getSessionId());
        });
    }

    @SneakyThrows
    public @Nullable List<SwarmStreamEvent> readEvents(@Nullable JsonNode tree) {
        if (tree == null || tree.isNull()) {
            return null;
        }
        return objectMapper.treeToValue(tree, objectMapper.getTypeFactory()
            .constructCollectionType(List.class, SwarmStreamEvent.class));
    }

    @Transactional(readOnly = true)
    public List<SessionAnalysis> findRunningAnalyses() {
        return analysisRepository.findByStatus(AnalysisStatus.RUNNING);
    }

    @Transactional(readOnly = true)
    public java.util.Optional<SessionAnalysis> findAnalysis(String runId) {
        return analysisRepository.findById(runId);
    }

    @Transactional
    public void touch(String sessionId) {
        sessionRepository.findById(sessionId).ifPresent(s -> {
            s.touch();
            sessionRepository.save(s);
        });
    }

    @Transactional
    public void registerImport(String sessionId, String jobId) {
        touch(sessionId);
        importRepository.save(new SessionImport(jobId, sessionId));
    }

    @Transactional(readOnly = true)
    public List<SessionAnalysis> analysesFor(String sessionId) {
        return analysisRepository.findBySessionIdOrderByStartedAtDesc(sessionId);
    }

    @Transactional(readOnly = true)
    public List<String> importJobIdsFor(String sessionId) {
        return importRepository.findBySessionIdOrderByLinkedAtDesc(sessionId).stream()
            .map(SessionImport::getJobId)
            .toList();
    }
}
