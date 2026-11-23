package com.life.life_backend.identity;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

interface GuestOwnerRepository extends JpaRepository<GuestOwner, UUID> {
	Optional<GuestOwner> findByTokenHashAndExpiresAtAfter(String hash, Instant now);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select owner from GuestOwner owner where owner.id = :id and owner.expiresAt > :now")
	Optional<GuestOwner> lockActiveOwner(UUID id, Instant now);
}
