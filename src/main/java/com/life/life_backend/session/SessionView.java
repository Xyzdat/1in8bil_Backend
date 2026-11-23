package com.life.life_backend.session;

import com.life.life_backend.engine.BirthDetails;
import com.life.life_backend.engine.CharacterProfile;
import com.life.life_backend.engine.LifeState;

import java.time.Instant;
import java.util.UUID;

record SessionView(UUID id, Character character, long version, Instant savedAt, LifeState state, String provider) {
	record Character(String name, String gender, Integer birthYear, CharacterProfile.Birthplace birthplace,
			CharacterProfile.Background background, BirthDetails birthDetails) {
	}

	record Summary(UUID id, String name, int age, LifeState.Status status, Instant savedAt, String provider) {
	}
}
