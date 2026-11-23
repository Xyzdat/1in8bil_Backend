package com.life.life_backend.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "guest_owner")
class GuestOwner {
	@Id
	private UUID id;

	@Column(nullable = false, length = 64)
	private String tokenHash;

	@Column(nullable = false)
	private Instant expiresAt;

	protected GuestOwner() {
	}

	GuestOwner(UUID id, String tokenHash, Instant expiresAt) {
		this.id = id;
		this.tokenHash = tokenHash;
		this.expiresAt = expiresAt;
	}

	UUID id() {
		return id;
	}
}
