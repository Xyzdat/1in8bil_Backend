package com.life.life_backend.engine;

import com.life.life_backend.economy.ProviderEconomy;

import java.time.Instant;

public interface LifeEngine {
	String id();

	default boolean available() {
		return true;
	}

	Checkpoint advance(Checkpoint current);

	Checkpoint answer(Checkpoint current, String optionId);

	record Checkpoint(LifeState state, Cursor cursor, ProviderEconomy providerEconomy) {
		public Checkpoint(LifeState state, Cursor cursor) {
			this(state, cursor, null);
		}
	}

	record Cursor(String token, Instant expiresAt) {
	}
}
