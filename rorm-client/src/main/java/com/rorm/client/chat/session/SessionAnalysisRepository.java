package com.rorm.client.chat.session;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SessionAnalysisRepository extends JpaRepository<SessionAnalysis, String> {
    List<SessionAnalysis> findBySessionIdOrderByStartedAtDesc(String sessionId);

    List<SessionAnalysis> findByStatus(AnalysisStatus status);
}
