package com.github.epsilon.modules.impl.player;

import com.github.epsilon.events.bus.EventHandler;
import com.github.epsilon.events.impl.ClientTickEvent;
import com.github.epsilon.events.impl.LevelUpdateEvent;
import com.github.epsilon.events.impl.PacketEvent;
import com.github.epsilon.modules.Category;
import com.github.epsilon.modules.Module;
import com.github.epsilon.settings.impl.BoolSetting;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.protocol.game.ClientboundResetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.util.HashMap;
import java.util.Map;

public class HealthBypass extends Module {

    public static final HealthBypass INSTANCE = new HealthBypass();

    private HealthBypass() {
        super("Health Bypass", Category.PLAYER);
    }

    private final BoolSetting spoofHealth = boolSetting("Spoof Health", true);
    private final BoolSetting emoji = boolSetting("Emoji", true);

    private final Map<String, Float> healths = new HashMap<>();

    @Override
    protected void onEnable() {
        healths.clear();
    }

    @Override
    protected void onDisable() {
        healths.clear();
    }

    @EventHandler
    private void onLevelUpdate(LevelUpdateEvent event) {
        healths.clear();
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (nullCheck()) return;

        // 分支 A：拦截下发给自己的假血量（服务器伪装的超满血）
        if (spoofHealth.getValue() && event.getPacket() instanceof ClientboundSetHealthPacket healthPacket) {
            if (healthPacket.getHealth() > 20.0F) {
                event.cancel();
            }
            return;
        }

        // 分支 B：Tab 列表 HEARTS objective 的实时分数推送
        if (emoji.getValue() && event.getPacket() instanceof ClientboundSetScorePacket scorePacket) {
            handleScorePacket(scorePacket);
        } else if (emoji.getValue() && event.getPacket() instanceof ClientboundResetScorePacket resetPacket) {
            handleScoreReset(resetPacket);
        }
    }

    @EventHandler
    private void onClientTick(ClientTickEvent.Pre event) {
        if (nullCheck()) return;

        if (emoji.getValue()) {
            readEmojiScores();
        }

        if (healths.isEmpty()) return;

        // 每 tick 全量写回，防止服务端的元数据假血量覆盖真实值
        for (AbstractClientPlayer player : mc.level.players()) {
            if (player == mc.player) continue;

            Float health = healths.get(player.getGameProfile().name());
            if (health != null) {
                player.setHealth(Math.max(0.0F, health));
            }
        }
    }

    private void handleScorePacket(ClientboundSetScorePacket packet) {
        String name = packet.owner();
        if (name.equals(mc.player.getGameProfile().name())) return;

        Objective listObjective = getListHeartsObjective();
        if (listObjective != null && listObjective.getName().equalsIgnoreCase(packet.objectiveName())) {
            healths.put(name, (float) packet.score());
        }
    }

    private void handleScoreReset(ClientboundResetScorePacket packet) {
        Objective listObjective = getListHeartsObjective();
        if (listObjective != null && listObjective.getName().equalsIgnoreCase(packet.objectiveName())) {
            healths.remove(packet.owner());
        }
    }

    /**
     * Emoji 模式核心判据：LIST 槽位挂载了 HEARTS 渲染的 objective，
     * 其分数在语义上就是服务器下发的真实血量。
     */
    private Objective getListHeartsObjective() {
        Objective objective = mc.level.getScoreboard().getDisplayObjective(DisplaySlot.LIST);
        if (objective == null || objective.getRenderType() != ObjectiveCriteria.RenderType.HEARTS) {
            return null;
        }
        return objective;
    }

    private void readEmojiScores() {
        Objective objective = getListHeartsObjective();
        if (objective == null) return;

        for (AbstractClientPlayer player : mc.level.players()) {
            if (player == mc.player) continue;

            String name = player.getGameProfile().name();
            var info = mc.level.getScoreboard().getPlayerScoreInfo(ScoreHolder.forNameOnly(name), objective);
            if (info != null && info.value() >= 0) {
                healths.put(name, (float) info.value());
            }
        }
    }

}
