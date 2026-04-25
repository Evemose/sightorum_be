package com.rorm.client.chat.session;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SessionService {

    public static final String SESSION_PREFIX = "session-";

    private final SessionRepository sessionRepository;
    private final SessionAnalysisRepository analysisRepository;
    private final SessionImportRepository importRepository;

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
