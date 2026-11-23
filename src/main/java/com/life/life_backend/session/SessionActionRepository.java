package com.life.life_backend.session;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface SessionActionRepository extends JpaRepository<SessionAction, UUID> {
	Optional<SessionAction> findBySessionIdAndRequestKey(UUID sessionId, UUID requestKey);
}
