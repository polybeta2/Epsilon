package com.github.epsilon.events.impl;

public class SlowdownEvent {

    private boolean slowdown;
    private float multiplier;
    private boolean multiplierSet;

    public SlowdownEvent(boolean slowdown) {
        this.slowdown = slowdown;
    }

    public boolean isSlowdown() {
        return slowdown;
    }

    public void setSlowdown(boolean slowdown) {
        this.slowdown = slowdown;
    }

    /**
     * 覆盖使用物品的移动输入倍率；仅在 hasMultiplier() 为 true 时由 Mixin 应用，
     * 否则保持原版按物品 USE_EFFECTS 组件计算的倍率。
     */
    public void setMultiplier(float multiplier) {
        this.multiplier = multiplier;
        this.multiplierSet = true;
    }

    public boolean hasMultiplier() {
        return multiplierSet;
    }

    public float getMultiplier() {
        return multiplier;
    }

}
