package com.rorm.client.chat.session;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SessionRepository extends JpaRepository<Session, String> {

    default List<Session> findAllMostRecent() {
        return findAll(Sort.by("updatedAt").descending());
    }
}
