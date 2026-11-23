package com.life.life_backend.session;

import com.life.life_backend.engine.CharacterProfile;
import com.life.life_backend.engine.StartingStats;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record CreateSessionRequest(
		@NotNull @Valid CharacterProfile character,
		@NotNull @Valid StartingStats startingStats
) {
}
