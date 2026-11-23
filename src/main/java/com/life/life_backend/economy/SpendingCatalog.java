package com.life.life_backend.economy;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@Component
class SpendingCatalog {
	private final Catalog data;

	SpendingCatalog(ObjectMapper json) throws IOException {
		try (var input = new ClassPathResource("spending-catalog.json").getInputStream()) {
			data = json.readValue(input, Catalog.class);
		}
	}

	List<Section> sections() {
		return data.sections();
	}

	List<String> petNames() {
		return data.petNames();
	}

	Optional<Section> section(String id) {
		return sections().stream().filter(section -> section.id().equals(id)).findFirst();
	}

	record Catalog(List<Section> sections, List<String> petNames) {
	}

	record Section(String id, String groupId, String groupTitle, String title, String heading, String description,
			String confirm, int minAge, List<Item> items, String actionTitle, String summary, String icon) {
		List<Item> itemsAt(FinanceView.Location location) {
			if (!id.equals("city")) {
				return items;
			}
			return items.stream().filter(item -> item.location().countryCode().equals(location.countryCode())).toList();
		}
	}

	record Item(String id, String title, String description, String icon, long price, Long yearlyCost,
			Integer durationMonths, int minAge, FinanceView.Effects effects, FinanceView.Location location) {
	}
}
