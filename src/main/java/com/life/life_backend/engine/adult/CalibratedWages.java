package com.life.life_backend.engine.adult;

import com.life.life_backend.statistics.SocioeconomicCalibration;
import java.util.*;

/** Applies a dimensionless, frozen survey ratio to the existing game wage scale. */
public final class CalibratedWages {
    private static final Map<String, String> GROUPS = Map.ofEntries(
            Map.entry("Инженер", "2"), Map.entry("Аналитик", "2"), Map.entry("Врач", "2"), Map.entry("Учитель", "2"),
            Map.entry("Координатор проектов", "3"), Map.entry("Руководитель", "1"),
            Map.entry("Мастер ремонта", "7"), Map.entry("Ремонтник", "7"), Map.entry("Оператор склада", "9"),
            Map.entry("Помощник повара", "5"), Map.entry("Специалист поддержки", "4"), Map.entry("Продавец", "5"),
            Map.entry("Водитель", "8"), Map.entry("Работник производства", "8"), Map.entry("Работник сельского хозяйства", "6"));

    private CalibratedWages() { }
    public static double factor(SocioeconomicCalibration calibration, String countryIso2, String occupation) {
        if (calibration == null) return 1; // Existing lives never opt in automatically.
        if (!SocioeconomicCalibration.VERSION.equals(calibration.modelVersion()))
            throw new IllegalStateException("Unsupported socioeconomic calibration version");
        var country = calibration.labour().get(countryIso2);
        if (country == null) return 1; // Relocation outside the pinned 36-country coverage.
        String group = GROUPS.getOrDefault(occupation == null ? "" : occupation, "TYPICAL");
        return country.occupationPayRatios().getOrDefault(group, country.occupationPayRatios().getOrDefault("TYPICAL", 1d));
    }
    public static long monthly(long gameAmount, SocioeconomicCalibration calibration, String countryIso2, String occupation) {
        if (calibration == null) return gameAmount;
        return Math.max(0, Math.round(gameAmount * factor(calibration, countryIso2, occupation)));
    }
}
