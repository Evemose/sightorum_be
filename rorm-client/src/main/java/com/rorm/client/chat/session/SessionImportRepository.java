package com.rorm.client.chat.session;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SessionImportRepository extends JpaRepository<SessionImport, SessionImport.Key> {
    List<SessionImport> findBySessionIdOrderByLinkedAtDesc(String sessionId);
}
