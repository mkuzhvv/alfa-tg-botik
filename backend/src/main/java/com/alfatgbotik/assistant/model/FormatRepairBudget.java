package com.alfatgbotik.assistant.model;

public final class FormatRepairBudget {

    private boolean used;

    public boolean take() {
        if (used) {
            return false;
        }
        used = true;
        return true;
    }
}
