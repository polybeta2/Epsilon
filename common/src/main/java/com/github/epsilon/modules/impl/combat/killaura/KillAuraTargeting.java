package com.github.epsilon.modules.impl.combat.killaura;

import com.github.epsilon.managers.target.TargetManager;
import com.github.epsilon.managers.target.TargetRequest;
import com.github.epsilon.modules.impl.movement.Velocity;
import com.github.epsilon.utils.rotation.RotationUtils;
import com.github.epsilon.utils.timer.TimerUtils;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.github.epsilon.Constants.mc;

/**
 * KillAura 的目标选择拆分层：候选收集、Switch 切换计时与优先级排序，输出当前目标。
 */
class KillAuraTargeting {

    private final List<LivingEntity> targets = new ArrayList<>();
    private int targetIndex;
    private final TimerUtils switchTimer = new TimerUtils();

    LivingEntity select(KillAura aura) {
        targets.clear();
        targets.addAll(TargetManager.INSTANCE.acquireTargets(TargetRequest.of(
                aura.searchRange.getValue(),
                aura.fov.getValue().floatValue(),
                aura.players.getValue(),
                aura.mobs.getValue(),
                aura.animals.getValue(),
                aura.villagers.getValue(),
                aura.ambient.getValue(),
                aura.water.getValue(),
                aura.others.getValue(),
                aura.invisible.getValue(),
                64
        )));

        Velocity velocity = Velocity.INSTANCE;
        if (velocity.delay) {
            targets.sort(
                    Comparator.comparingDouble(o -> (double) Math.abs(velocity.yaw - RotationUtils.calculate(o).getYaw()))
            );
        }

        switch (aura.targetMode.getValue()) {
            case Single -> targetIndex = 0;
            case Switch -> {
                if (switchTimer.passedMillise(aura.switchDelay.getValue())) {
                    switchTimer.reset();
                    if (++targetIndex >= targets.size()) {
                        targetIndex = 0;
                    }
                }
            }
        }

        if (targetIndex >= targets.size()) {
            targetIndex = 0;
        }

        if (targets.isEmpty()) {
            return null;
        }

        switch (aura.priorityMode.getValue()) {
            case Range -> targets.sort(Comparator.comparingDouble(o -> (double) o.distanceTo(mc.player)));
            case Fov -> {
                targets.sort(Comparator.comparingDouble(o -> (double) Math.abs(Mth.wrapDegrees(mc.player.getXRot() - RotationUtils.calculate(o).getYaw()))));
            }
            case Health -> {
                targets.sort(Comparator.comparingDouble(o -> o instanceof LivingEntity living ? (double) living.getHealth() : 0.0));
            }
        }

        return targets.get(targetIndex);
    }

}
