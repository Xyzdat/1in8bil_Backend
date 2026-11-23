package com.life.life_backend.economy;

import java.math.BigDecimal;
import java.util.List;

public record ProviderEconomy(int age, BigDecimal income, BigDecimal netWorth, String occupation, List<Option> options) {
	public record Option(String id, String name, String description, BigDecimal price, Boolean available, String reason) {
	}
}
