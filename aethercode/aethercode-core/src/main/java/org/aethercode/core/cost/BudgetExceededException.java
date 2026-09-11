package org.aethercode.core.cost;

/**
 * thrown by {@link CostBudget#consume} when the daily or per-call
 * cap would be exceeded.
 */
public class BudgetExceededException extends RuntimeException {
    public BudgetExceededException(String message) { super(message); }
}
