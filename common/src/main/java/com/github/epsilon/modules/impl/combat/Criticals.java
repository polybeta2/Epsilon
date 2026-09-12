package com.github.epsilon.modules.impl.combat;

import com.github.epsilon.modules.impl.combat.killaura.KillAura;
import com.github.epsilon.events.bus.EventHandler;
import com.github.epsilon.events.bus.EventPriority;
import com.github.epsilon.events.impl.AttackEntityEvent;
import com.github.epsilon.events.impl.ClientTickEvent;
import com.github.epsilon.events.impl.KeyboardInputEvent;
import com.github.epsilon.modules.Category;
import com.github.epsilon.modules.Module;
import com.github.epsilon.modules.impl.movement.NoPacketSprint;
import com.github.epsilon.modules.impl.movement.Velocity;
import com.github.epsilon.settings.impl.BoolSetting;
import net.minecraft.world.entity.LivingEntity;

public class Criticals extends Module {

    public static final Criticals INSTANCE = new Criticals();

    private Criticals() {
        super("Criticals", Category.COMBAT);
    }

    /**
     * Loftily JumpCriticals 的移植：每次攻击（手动或 KillAura）时若在地面则自动跳跃，
     * 使后续的攻击在落弧中命中而触发暴击；与 KillAura 的 No Double Hit 配合
     * 形成"地面命中 → 跳 → 空中暴击"的节奏。
     */
    private final BoolSetting autoJump = boolSetting("Auto Jump", false);

    public int fallTicks;
    private boolean stopSprinting;

    @Override
    protected void onDisable() {
        fallTicks = 0;
        stopSprinting = false;
    }

    @EventHandler
    private void onAttack(AttackEntityEvent event) {
        if (!autoJump.getValue() || nullCheck()) return;

        if (event.getEntity() instanceof LivingEntity && mc.player.onGround()) {
            mc.player.jumpFromGround();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onClientTick(ClientTickEvent.Pre event) {
        if (nullCheck() || !canCrit() || mc.player.fallDistance >= 1.0f) {
            fallTicks = 0;
        } else {
            fallTicks++;
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private void prepareSprintStop(ClientTickEvent.Pre event) {
        // NoPacketSprint 开启时服务端始终认为玩家未疾跑，命中本就无疾跑效果，无需停止
        stopSprinting = !nullCheck()
                && !NoPacketSprint.INSTANCE.isEnabled()
                && fallTicks > 0
                && fallTicks < 3
                && mc.player.isSprinting()
                && KillAura.INSTANCE.target != null
                && Velocity.INSTANCE.attackQueue == 0;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private void onKeyboardInput(KeyboardInputEvent event) {
        if (!stopSprinting) return;
        stopSprinting = false;

        if (fallTicks > 0
                && fallTicks < 3
                && canCrit()
                && mc.player.isSprinting()
                && KillAura.INSTANCE.target != null
                && Velocity.INSTANCE.attackQueue == 0
                && event.getForward() > 0.0f) {
            event.setSprint(false);
            mc.player.setSprinting(false);
            mc.options.keySprint.setDown(false);
        }
    }

    private boolean canCrit() {
        return mc.player.fallDistance > 0.0 && !mc.player.onGround() && !mc.player.onClimbable() && !mc.player.isInWater() && !mc.player.isMobilityRestricted() && !mc.player.isPassenger();
    }

}
