package com.github.epsilon.modules.impl.combat.killaura;

import com.github.epsilon.Constants;
import com.github.epsilon.managers.rotation.RotationManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import static com.github.epsilon.Constants.mc;

/**
 * KillAura 的 AutoBlock 拆分层。
 *
 * <p>Matrix / Matrix1.12 面向 1.8.9 服务器或带 1.8 格挡插件的服务器：1.8 的格挡是
 * "右键剑"——服务端收到主手 UseItem 包且手持剑时进入格挡状态（经 ViaVersion 翻译或
 * 格挡插件均按此语义处理），因此这里不要求持有盾牌，只要求主手持剑。</p>
 *
 * <p>Matrix 模式：目标处于格挡范围内时持续发主手 UseItem 包维持格挡，攻击瞬间按
 * C07(释放)→攻击→C08(补格挡) 括弧发包，保证攻击到达服务端时不处于格挡状态。</p>
 *
 * <p>Matrix1.12 模式为两 tick 状态机：攻击后立即重新格挡并置 testBlocking，下一 tick
 * 先释放格挡并跳过攻击，再下一 tick 才发起攻击——把"释放"与"攻击"拆到不同 tick。</p>
 */
class KillAuraAutoBlock {

    private final KillAura aura;
    private boolean blocking;
    private boolean blockingState;
    private boolean testBlocking;

    KillAuraAutoBlock(KillAura aura) {
        this.aura = aura;
    }

    /**
     * 每 tick 维护：Matrix 在范围内持续格挡；Matrix1.12 不主动格挡（格挡只发生在攻击括弧内），
     * 超距/目标丢失时收格挡。
     */
    void tick(LivingEntity target) {
        switch (aura.autoBlockMode.getValue()) {
            case None -> reset();
            case Matrix -> {
                if (canBlock(target, "tick")) {
                    if (!blocking) {
                        sendBlockUse("maintain");
                        blocking = true;
                    }
                } else {
                    reset();
                }
            }
            case Matrix1_12 -> {
                if (!canBlock(target, "tick")) {
                    reset();
                }
            }
        }
    }

    /**
     * Matrix1.12 的跳过 tick：上一 tick 攻击后已重新格挡，本 tick 先释放并跳过攻击。
     *
     * @return true 表示本 tick 应跳过攻击（调用方需保留攻击预算）
     */
    boolean skipTickForMatrix112() {
        if (!aura.autoBlockMode.is(KillAura.AutoBlockMode.Matrix1_12) || !testBlocking) {
            return false;
        }
        releaseBlock("matrix112-skip");
        testBlocking = false;
        blockingState = false;
        blocking = false;
        debug("Matrix1.12 状态机：本 tick 释放格挡并跳过攻击");
        return true;
    }

    /**
     * 攻击前释放格挡（Matrix 模式）；Matrix1.12 在攻击 tick 时服务端已处于未格挡状态。
     *
     * @return 攻击后是否需要补格挡
     */
    boolean beginAttack() {
        if (!blockingState) {
            return false;
        }
        releaseBlock("attack-bracket");
        blockingState = false;
        return true;
    }

    /**
     * Matrix 模式攻击后重新格挡，维持服务端格挡状态。
     */
    void endAttack(boolean wasBlocking) {
        if (!wasBlocking) {
            return;
        }
        sendBlockUse("matrix-reblock");
    }

    /**
     * Matrix1.12 攻击后的重新格挡：interactAt（可选）+ UseItem，并置 testBlocking 进入跳过相位。
     */
    void postAttack(Entity target) {
        if (!aura.autoBlockMode.is(KillAura.AutoBlockMode.Matrix1_12)) {
            return;
        }
        if (aura.interactAutoBlock.getValue() && target != null) {
            // 26.2 的 interact 包 location 为相对实体的偏移（与 MultiPlayerGameMode.interact 一致）
            Vec3 location = target.getEyePosition().subtract(target.getX(), target.getY(), target.getZ());
            mc.getConnection().send(new ServerboundInteractPacket(
                    target.getId(), InteractionHand.MAIN_HAND, location, mc.player.isShiftKeyDown()));
            debug("发送 interactAt 维持交互状态");
        }
        sendBlockUse("matrix112-reblock");
        blocking = true;
        blockingState = true;
        testBlocking = true;
    }

    /**
     * 收格挡并清除全部状态；服务端仍认为格挡中时先补发释放包。
     */
    void reset() {
        if (blockingState) {
            releaseBlock("reset");
        }
        blocking = false;
        blockingState = false;
        testBlocking = false;
    }

    private boolean canBlock(LivingEntity target, String phase) {
        if (target == null) {
            debug(phase + ": 无目标，不格挡");
            return false;
        }
        if (!aura.isEnabled()) {
            debug(phase + ": 模块未启用，不格挡");
            return false;
        }
        if (!isHoldingSword()) {
            debug(phase + ": 主手未持有剑，不格挡");
            return false;
        }
        double rangeSq = Mth.square(aura.blockRange.getValue());
        double distSq = mc.player.distanceToSqr(target);
        if (distSq > rangeSq) {
            debug(phase + ": 目标超距 " + String.format("%.2f", Math.sqrt(distSq)) + " > " + aura.blockRange.getValue() + "，不格挡");
            return false;
        }
        return true;
    }

    private boolean isHoldingSword() {
        return mc.player.getMainHandItem().is(ItemTags.SWORDS);
    }

    /**
     * 发送主手 UseItem 启动 1.8 语义的剑格挡；对现代原版服务器剑无使用动作，包会被忽略。
     */
    private void sendBlockUse(String reason) {
        // 与原版 MultiPlayerGameMode.startPrediction 相同的 sequence 配对方式
        try (var prediction = mc.level.getBlockStatePredictionHandler().startPredicting()) {
            int sequence = prediction.currentSequence();
            mc.getConnection().send(new ServerboundUseItemPacket(
                    InteractionHand.MAIN_HAND,
                    sequence,
                    RotationManager.INSTANCE.getYaw(),
                    RotationManager.INSTANCE.getPitch()
            ));
            debug("格挡 UseItem seq=" + sequence + " 原因=" + reason);
        }
        blockingState = true;
    }

    private void releaseBlock(String reason) {
        mc.getConnection().send(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM,
                BlockPos.ZERO,
                Direction.DOWN
        ));
        debug("释放格挡 原因=" + reason);
    }

    private void debug(String message) {
        if (aura.autoBlockDebug.getValue()) {
            Constants.LOGGER.info("[AutoBlock] {}", message);
        }
    }

}
