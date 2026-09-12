package com.github.epsilon.modules.impl.movement;

import com.github.epsilon.events.bus.EventHandler;
import com.github.epsilon.events.impl.PacketEvent;
import com.github.epsilon.modules.Category;
import com.github.epsilon.modules.Module;
import com.github.epsilon.settings.impl.BoolSetting;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;

public class NoPacketSprint extends Module {

    public static final NoPacketSprint INSTANCE = new NoPacketSprint();

    private NoPacketSprint() {
        super("No Packet Sprint", Category.MOVEMENT);
    }

    public final BoolSetting allDir = boolSetting("All Dir", false);

    @Override
    protected void onDisable() {
        if (nullCheck()) return;
        // 服务端从未得知疾跑状态，禁用时直接恢复客户端疾跑并让其自然同步
        mc.player.setSprinting(false);
    }

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (nullCheck()) return;

        if (event.getPacket() instanceof ServerboundPlayerCommandPacket packet
                && (packet.getAction() == ServerboundPlayerCommandPacket.Action.START_SPRINTING
                || packet.getAction() == ServerboundPlayerCommandPacket.Action.STOP_SPRINTING)) {
            event.cancel();
        }
    }

    /**
     * AllDir：原版校验会在失去前向输入时停止疾跑；只要玩家仍在移动（含侧向/后退），
     * 就让 shouldStopRunSprinting 的前向判定保持为真，使 travel 继续吃到疾跑速度加成。
     * 由 MixinLocalPlayer 在 shouldStopRunSprinting 中调用。
     */
    public boolean shouldKeepSprint() {
        if (!isEnabled() || !allDir.getValue()) return false;

        var moveVector = mc.player.input.getMoveVector();
        return Math.abs(moveVector.x) > 1.0E-5F || Math.abs(moveVector.y) > 1.0E-5F;
    }

}
