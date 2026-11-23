package com.life.life_backend.engine;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record StartingStats(
		@NotNull @Min(0) @Max(100) Integer health,
		@NotNull @Min(0) @Max(100) Integer intelligence,
		@NotNull @Min(0) @Max(100) Integer appearance
) {
}
