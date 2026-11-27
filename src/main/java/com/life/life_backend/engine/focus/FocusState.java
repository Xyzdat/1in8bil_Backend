package com.life.life_backend.engine.focus;

import java.util.*;

/** Fractional accrual is saved. Selection itself never advances time or grants points. */
public record FocusState(String modelVersion, String id, int changedMonth, int settledMonth,
                         Map<String,Integer> remainders, List<Change> changes, Applied lastApplied) {
    public static final String VERSION = "year-focus-1";
    public FocusState { remainders=Map.copyOf(remainders); changes=List.copyOf(changes); }
    public static FocusState start(int month) { return new FocusState(VERSION,"balanced",month,month,Map.of(),List.of(),null); }
    public FocusState select(String chosen, int month) {
        if(id.equals(chosen))return this;
        var history=new ArrayList<>(changes);
        history.removeIf(change -> change.month()==month); history.add(new Change(month,chosen));
        return new FocusState(modelVersion,chosen,month,month,remainders,history,lastApplied);
    }
    public record Change(int month,String focusId) { }
    public record Applied(String focusId,int month,Map<String,Integer> stats,Map<String,Integer> skills,Map<String,Integer> closeness) {
        public Applied { stats=Map.copyOf(stats);skills=Map.copyOf(skills);closeness=Map.copyOf(closeness); }
    }
}
