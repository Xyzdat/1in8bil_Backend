package com.life.life_backend.engine;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CharacterProfile(
		@NotBlank @Size(max = 100) String name,
		@NotBlank @Size(max = 50) String gender,
		@NotNull @Min(1) @Max(9999) Integer birthYear,
		@NotNull @Valid Birthplace birthplace,
		@Valid Background background
) {
	public record Birthplace(
			@NotNull @Pattern(regexp = "[A-Z]{2}") String countryCode,
			@NotBlank @Size(max = 100) String country,
			@Size(max = 100) String region,
			@Size(max = 100) String city
	) {
	}

	public record Background(
			@Size(max = 120) String family,
			@Size(max = 120) String siblings,
			@Size(max = 120) String pets,
			@Size(max = 120) String housing,
			@Size(max = 120) String relationships,
			@Size(max = 120) String father,
			@Size(max = 120) String mother,
			@Size(max = 120) String wealth,
			@Size(max = 120) String religion,
			@Size(max = 120) String language
	) {
	}
}
