package com.life.life_backend.session;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface LifeSessionRepository extends JpaRepository<LifeSession, UUID> {
	Optional<LifeSession> findByOwnerIdAndCreationKey(UUID ownerId, UUID creationKey);

	Optional<LifeSession> findByIdAndOwnerId(UUID id, UUID ownerId);

	List<LifeSession> findAllByOwnerIdOrderBySavedAtDescIdAsc(UUID ownerId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select session from LifeSession session where session.id = :id and session.ownerId = :ownerId")
	Optional<LifeSession> lockByIdAndOwnerId(UUID id, UUID ownerId);
}
