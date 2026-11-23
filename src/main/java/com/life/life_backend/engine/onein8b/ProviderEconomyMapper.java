package com.life.life_backend.engine.onein8b;

import com.life.life_backend.economy.ProviderEconomy;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

final class ProviderEconomyMapper {
	private ProviderEconomyMapper() {
	}

	static ProviderEconomy map(JsonNode state, JsonNode sheet, int age, boolean ended) {
		var income = amount(state.path("income_ppp"));
		var netWorth = amount(state.path("net_worth_ppp"));
		var occupation = text(state.path("job_name"));
		if (ended && income == null && netWorth == null && occupation == null) {
			return null;
		}
		return new ProviderEconomy(age, income, netWorth, occupation, ended ? List.of() : options(sheet));
	}

	private static List<ProviderEconomy.Option> options(JsonNode sheet) {
		if (!sheet.isArray()) {
			return null;
		}
		var result = new ArrayList<ProviderEconomy.Option>();
		for (var item : sheet) {
			var id = text(item.path("id"));
			var name = text(item.path("name"));
			var price = amount(item.path("price_ppp"));
			if (id != null && name != null && price != null && price.signum() >= 0) {
				var available = item.path("can");
				result.add(new ProviderEconomy.Option(id, name, text(item.path("does")), price,
						available.isBoolean() ? available.asBoolean() : null, text(item.path("why"))));
			}
		}
		return List.copyOf(result);
	}

	private static BigDecimal amount(JsonNode node) {
		return node.isNumber() ? node.decimalValue() : null;
	}

	private static String text(JsonNode node) {
		return node.isString() && !node.asString().isBlank() ? node.asString() : null;
	}
}
