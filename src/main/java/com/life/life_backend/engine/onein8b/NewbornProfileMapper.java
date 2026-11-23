package com.life.life_backend.engine.onein8b;

import com.life.life_backend.engine.BirthDetails;
import com.life.life_backend.engine.CharacterProfile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.life.life_backend.engine.onein8b.OneInEightBillionClient.invalidResponse;

@Component
class NewbornProfileMapper {
	private static final Locale RUSSIAN = Locale.forLanguageTag("ru");
	private final OneInEightBillionClient client;

	NewbornProfileMapper(OneInEightBillionClient client) {
		this.client = client;
	}

	Profile map(JsonNode state, JsonNode family) {
		var country = countryLocale(state.path("born_in").asString(""));
		var sex = sex(state.path("sex"));
		var year = nonNegativeInt(state.path("born_year"));
		var name = text(state.path("name"));
		if (name == null || sex == null || year == null || year < 1 || year > 9999) {
			throw invalidResponse();
		}

		var birthplace = new CharacterProfile.Birthplace(country.getCountry(), country.getDisplayCountry(RUSSIAN),
				null, text(state.path("city")));
		var who = state.path("who");
		var details = new BirthDetails(placeType(state.path("place")), familyType(family.path("parents")),
				nonNegativeInt(family.path("older_siblings")), incomeQuintile(family.path("family_fifth")),
				flag(family.path("father_there")), parent(state.path("mother"), family.path("mother_age")),
				parent(state.path("father"), family.path("father_age")), siblings(state.path("siblings")),
				household(state.path("household")), flag(state.path("home_owned")),
				flag(state.path("electricity_at_home")), text(who.path("faith")), category(who.path("group")),
				category(who.path("caste")), codes(who.path("disability")));
		var character = new CharacterProfile(name, sex == BirthDetails.Sex.FEMALE ? "Женщина" : "Мужчина",
				year, birthplace, background(details));
		return new Profile(character, details);
	}

	private CharacterProfile.Background background(BirthDetails details) {
		String family = switch (details.familyType()) {
			case MOTHER_AND_FATHER -> Boolean.FALSE.equals(details.fatherPresent())
					? "Мать, отец отсутствует" : "Мать и отец";
			case TWO_MOTHERS -> "Две матери";
			case TWO_FATHERS -> "Два отца";
			case null -> null;
		};
		String siblings = details.olderSiblingCount() == null ? null : details.olderSiblingCount().toString();
		String wealth = details.familyIncomeQuintile() == null ? null
				: "Доходная группа " + details.familyIncomeQuintile() + " из 5";
		String religion = switch (details.faithCode()) {
			case "none" -> "Без религии";
			case "christian" -> "Христианство";
			case "muslim" -> "Ислам";
			case "hindu" -> "Индуизм";
			case "buddhist" -> "Буддизм";
			case "jewish" -> "Иудаизм";
			case "folk" -> "Народная религия";
			case "sikh" -> "Сикхизм";
			case "other" -> "Другая религия";
			case null, default -> null;
		};
		String father = occupationLabel(details.father());
		String mother = occupationLabel(details.mother());
		if (family == null && siblings == null && wealth == null && religion == null && father == null && mother == null) {
			return null;
		}
		return new CharacterProfile.Background(family, siblings, null, null, null, father, mother, wealth, religion, null);
	}

	private BirthDetails.Parent parent(JsonNode state, JsonNode birthAge) {
		Integer age = nonNegativeInt(birthAge);
		if (age == null) {
			age = nonNegativeInt(state.path("age"));
		}
		String code = text(state.path("job"));
		var occupation = code == null ? null : new BirthDetails.Occupation(code,
				"homemaker".equals(code) ? "ведение домашнего хозяйства" : client.jobLabels().get(code));
		var alive = flag(state.path("alive"));
		var livesWithChild = flag(state.path("at_home"));
		return age == null && occupation == null && alive == null && livesWithChild == null ? null
				: new BirthDetails.Parent(age, occupation, alive, livesWithChild);
	}

	private String occupationLabel(BirthDetails.Parent parent) {
		return parent == null || parent.occupation() == null ? null : parent.occupation().label();
	}

	private List<BirthDetails.Sibling> siblings(JsonNode siblings) {
		if (!siblings.isArray()) {
			return null;
		}
		var result = new ArrayList<BirthDetails.Sibling>();
		for (var sibling : siblings) {
			if (sibling.isObject()) {
				result.add(new BirthDetails.Sibling(nonNegativeInt(sibling.path("age")), sex(sibling.path("sex")),
						flag(sibling.path("older")), flag(sibling.path("at_home"))));
			}
		}
		return List.copyOf(result);
	}

	private BirthDetails.Household household(JsonNode household) {
		if (!household.isObject()) {
			return null;
		}
		var with = household.path("with");
		return new BirthDetails.Household(nonNegativeInt(household.path("size")), flag(with.path("mother")),
				flag(with.path("father")), flag(with.path("stepfather")), flag(with.path("partner")),
				flag(with.path("other_wife")), nonNegativeInt(with.path("siblings")),
				nonNegativeInt(with.path("children")), nonNegativeInt(with.path("stepchildren")));
	}

	private BirthDetails.Category category(JsonNode value) {
		var code = text(value.path("id"));
		return code == null ? null : new BirthDetails.Category(code, text(value.path("name")));
	}

	private List<String> codes(JsonNode values) {
		if (!values.isArray()) {
			return null;
		}
		var result = new ArrayList<String>();
		for (var value : values) {
			var code = text(value);
			if (code != null) {
				result.add(code);
			}
		}
		return List.copyOf(result);
	}

	private BirthDetails.PlaceType placeType(JsonNode place) {
		return switch (place.asString("")) {
			case "town" -> BirthDetails.PlaceType.URBAN;
			case "village" -> BirthDetails.PlaceType.RURAL;
			default -> null;
		};
	}

	private BirthDetails.FamilyType familyType(JsonNode family) {
		return switch (family.asString("")) {
			case "mother_and_father" -> BirthDetails.FamilyType.MOTHER_AND_FATHER;
			case "two_mothers" -> BirthDetails.FamilyType.TWO_MOTHERS;
			case "two_fathers" -> BirthDetails.FamilyType.TWO_FATHERS;
			default -> null;
		};
	}

	private BirthDetails.Sex sex(JsonNode sex) {
		return switch (sex.asString("")) {
			case "F", "female" -> BirthDetails.Sex.FEMALE;
			case "M", "male" -> BirthDetails.Sex.MALE;
			default -> null;
		};
	}

	private Integer incomeQuintile(JsonNode value) {
		var group = nonNegativeInt(value);
		return group != null && group >= 1 && group <= 5 ? group : null;
	}

	private Integer nonNegativeInt(JsonNode value) {
		return value.isIntegralNumber() && value.canConvertToInt() && value.asInt() >= 0 ? value.asInt() : null;
	}

	private Boolean flag(JsonNode value) {
		return value.isBoolean() ? value.asBoolean() : null;
	}

	private String text(JsonNode value) {
		String text = value.isString() ? value.asString() : "";
		return text.isBlank() ? null : text;
	}

	private Locale countryLocale(String iso3) {
		if ("XKX".equals(iso3)) {
			return Locale.of("", "XK");
		}
		for (String iso2 : Locale.getISOCountries()) {
			var country = Locale.of("", iso2);
			if (country.getISO3Country().equals(iso3)) {
				return country;
			}
		}
		throw invalidResponse();
	}

	record Profile(CharacterProfile character, BirthDetails details) {
	}
}
