package com.life.life_backend.highlights;

import com.life.life_backend.economy.LocalBudget;
import com.life.life_backend.engine.LifeState;
import com.life.life_backend.engine.adult.AdultState;
import com.life.life_backend.engine.local.LocalSimulation;

/** Immutable before/after snapshots, used only inside the existing session transaction. */
public record HighlightFrame(LifeState state, LocalSimulation simulation, AdultState adult, LocalBudget budget) {
    public int month() { return simulation.month(); }
}
