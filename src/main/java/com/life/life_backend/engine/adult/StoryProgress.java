package com.life.life_backend.engine.adult;

import java.util.*;

final class StoryProgress {
    final AdultState.Story story;
    final Map<String,String> variables;
    StoryProgress(AdultState.Story story) { this.story=story; variables=new HashMap<>(story.variables()); }
    String get(String key) { return variables.get(key); }
    void put(String key,Object value) { variables.put(key,String.valueOf(value)); }
    String actor() { return story.participants().isEmpty()?null:story.participants().getFirst(); }
    record Result(boolean proceed, String text) {
        static Result next(String text) { return new Result(true,text); }
        static Result end(String text) { return new Result(false,text); }
    }
}
