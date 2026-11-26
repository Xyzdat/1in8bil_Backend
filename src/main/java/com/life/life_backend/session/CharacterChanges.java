package com.life.life_backend.session;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import com.life.life_backend.engine.BirthDetails;
import java.util.List;

public record CharacterChanges(@Size(min = 1, max = 100) String name, @Valid Stats stats,
        BirthDetails.Sex sex, @Min(2023) @Max(2026) Integer birthYear,
        @Valid Birthplace birthplace, @Valid Family family) {
	public CharacterChanges {
		name = name == null ? null : name.strip();
	}

	boolean isEmpty() {
		return name == null && (stats == null || stats.isEmpty()) && !changesBirth();
	}

    boolean changesBirth() {
        return sex != null || birthYear != null || (birthplace != null && !birthplace.isEmpty()) || (family != null && !family.isEmpty());
    }

	@JsonAnySetter
	void rejectUnknownField(String field, Object value) {
		throw new IllegalArgumentException("Unknown character field.");
	}

	public record Stats(
			@Min(0) @Max(100) Integer health,
			@Min(0) @Max(100) Integer intelligence,
			@Min(0) @Max(100) Integer appearance,
            @Min(0) @Max(100) Integer happiness,
            @Min(0) @Max(100) Integer relationships,
            @Min(-100) @Max(100) Integer aura
	) {
		boolean editsEngineStats() {
			return health != null || intelligence != null || happiness != null || relationships != null || aura != null;
		}

		boolean isEmpty() {
			return !editsEngineStats() && appearance == null;
		}

		@JsonAnySetter
		void rejectUnknownField(String field, Object value) {
			throw new IllegalArgumentException("Unknown character stat.");
		}
	}

    public record Birthplace(@Pattern(regexp = "[A-Z]{2}") String countryCode,
            @Size(max = 100) String region, @Size(max = 100) String city, BirthDetails.PlaceType placeType) {
        public Birthplace { region = trim(region); city = trim(city); }
        boolean isEmpty() { return countryCode == null && region == null && city == null && placeType == null; }
        @JsonAnySetter void rejectUnknownField(String field, Object value) { throw new IllegalArgumentException("Unknown birthplace field."); }
    }

    public record Family(@Min(1) @Max(5) Integer incomeQuintile,
            @Size(min = 1, max = 120) String housing, @Size(min = 1, max = 120) String relationships,
            @Size(min = 1, max = 120) String religion, @Size(min = 1, max = 120) String language,
            @Size(max = 12) List<@NotNull @Valid Member> members) {
        public Family {
            housing = trim(housing); relationships = trim(relationships); religion = trim(religion); language = trim(language);
            if (members != null) members = List.copyOf(members);
        }
        boolean isEmpty() { return incomeQuintile == null && housing == null && relationships == null && religion == null && language == null && members == null; }
        @JsonAnySetter void rejectUnknownField(String field, Object value) { throw new IllegalArgumentException("Unknown family field."); }
    }

    /** Full member value at birth; omitted id creates a new member, supplied id preserves identity. */
    public record Member(@Size(min = 1, max = 80) String id,
            @NotNull @Pattern(regexp = "mother|father|brother|sister") String role,
            @NotBlank @Size(max = 100) String name, @NotNull @Min(0) @Max(80) Integer ageAtBirth,
            @Size(max = 100) String occupation, @Min(0) @Max(100) Integer closeness, @Min(0) @Max(100) Integer trust) {
        public Member { name = trim(name); occupation = trim(occupation); }
        @JsonAnySetter void rejectUnknownField(String field, Object value) { throw new IllegalArgumentException("Unknown family member field."); }
    }
    private static String trim(String value) { return value == null ? null : value.strip(); }
}
