package com.life.life_backend.engine;

import java.util.List;

public record BirthDetails(
		PlaceType placeType,
		FamilyType familyType,
		Integer olderSiblingCount,
		Integer familyIncomeQuintile,
		Boolean fatherPresent,
		Parent mother,
		Parent father,
		List<Sibling> siblings,
		Household household,
		Boolean homeOwned,
		Boolean electricityAtHome,
		String faithCode,
		Category group,
		Category caste,
		List<String> disabilityCodes
) {
	public enum PlaceType { URBAN, RURAL }

	public enum FamilyType { MOTHER_AND_FATHER, TWO_MOTHERS, TWO_FATHERS }

	public enum Sex { FEMALE, MALE }

	public record Parent(Integer age, Occupation occupation, Boolean alive, Boolean livesWithChild) {
	}

	public record Occupation(String code, String label) {
	}

	public record Sibling(Integer age, Sex sex, Boolean older, Boolean livesWithChild) {
	}

	public record Household(Integer size, Boolean mother, Boolean father, Boolean stepfather, Boolean partner,
			Boolean otherSpouse, Integer siblings, Integer children, Integer stepchildren) {
	}

	public record Category(String code, String name) {
	}
}
