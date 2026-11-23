package com.life.life_backend.session;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record CharacterChanges(@Size(min = 1, max = 100) String name, @Valid Stats stats) {
	public CharacterChanges {
		name = name == null ? null : name.strip();
	}

	boolean isEmpty() {
		return name == null && (stats == null || stats.isEmpty());
	}

	@JsonAnySetter
	void rejectUnknownField(String field, Object value) {
		throw new IllegalArgumentException("Unknown character field.");
	}

	public record Stats(
			@Min(0) @Max(100) Integer health,
			@Min(0) @Max(100) Integer intelligence,
			@Min(0) @Max(100) Integer appearance
	) {
		boolean editsEngineStats() {
			return health != null || intelligence != null;
		}

		boolean isEmpty() {
			return !editsEngineStats() && appearance == null;
		}

		@JsonAnySetter
		void rejectUnknownField(String field, Object value) {
			throw new IllegalArgumentException("Unknown character stat.");
		}
	}
}
