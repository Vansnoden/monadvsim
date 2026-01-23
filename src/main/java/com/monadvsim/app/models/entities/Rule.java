package com.monadvsim.app.models.entities;

import com.monadvsim.app.models.engine.TimeManager;

/**
 * A Rule defines a conditional behavior.
 * Example: IF (Temperature > 30) THEN (Increase Mortality)
 */
public abstract class Rule {
    private String name;
    private int priority;

    public Rule(String name, int priority) {
        this.name = name;
        this.priority = priority;
    }

    /**
     * Evaluates and applies the rule to a specific agent.
     * @return true if the rule was triggered/applied.
     */
    public abstract boolean apply(Agent agent, Project project, TimeManager timeManager);

    public String getName() { return name; }
    public int getPriority() { return priority; }
}
