package com.github.epsilon.modules.impl.render;

import com.github.epsilon.events.bus.EventHandler;
import com.github.epsilon.events.bus.EventPriority;
import com.github.epsilon.events.impl.ClientTickEvent;
import com.github.epsilon.events.impl.LevelUpdateEvent;
import com.github.epsilon.events.impl.PacketEvent;
import com.github.epsilon.events.impl.Render3DEvent;
import com.github.epsilon.graphics.schedulers.render3d.Render3DScheduler;
import com.github.epsilon.modules.Category;
import com.github.epsilon.modules.Module;
import com.github.epsilon.settings.SettingGroup;
import com.github.epsilon.settings.impl.BoolSetting;
import com.github.epsilon.settings.impl.ColorSetting;
import com.github.epsilon.settings.impl.DoubleSetting;
import com.github.epsilon.settings.impl.IntSetting;
import com.github.epsilon.utils.player.ChatUtils;
import com.github.epsilon.utils.player.KnockbackPredictor;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.world.phys.Vec3;

import java.awt.*;
import java.util.List;

public class VelocityPredict extends Module {

    public static final VelocityPredict INSTANCE = new VelocityPredict();

    private VelocityPredict() {
        super("Velocity Predict", Category.RENDER);
    }

    private static final double MOMENTUM_EPSILON_SQUARED = 1.0E-6D;

    private final SettingGroup generalGroup = settingGroup("General");
    private final SettingGroup renderGroup = settingGroup("Render");

    private final IntSetting predictTicks = intSetting("Predict Ticks", 60, 5, 200, 5).group(generalGroup);
    private final IntSetting displayTicks = intSetting("Display Ticks", 60, 5, 200, 5).group(generalGroup);
    private final BoolSetting includeExplosions = boolSetting("Include Explosions", true).group(generalGroup);
    private final BoolSetting warnVoid = boolSetting("Void Warning", true).group(generalGroup);

    private final BoolSetting showPath = boolSetting("Show Path", true).group(renderGroup);
    private final BoolSetting showLandingBox = boolSetting("Show Landing Box", true).group(renderGroup);
    private final DoubleSetting lineWidth = doubleSetting("Line Width", 1.5, 0.5, 4.0, 0.25, showPath::getValue).group(renderGroup);
    private final ColorSetting pathColor = colorSetting("Path Color", new Color(36, 178, 255), showPath::getValue).group(renderGroup);
    private final ColorSetting landingColor = colorSetting("Landing Color", new Color(41, 255, 255), showLandingBox::getValue).group(renderGroup);

    private Vec3 velocityBeforePacket = Vec3.ZERO;
    private KnockbackPredictor.Result prediction;
    private int remainingDisplayTicks;
    private long lastVoidWarningMillis;

    @Override
    protected void onDisable() {
        clearPrediction();
    }

    /**
     * 在 Velocity 等默认优先级的监听器之前快照击退前的速度；
     * 被取消的包不会继续分发到本模块的 LOWEST 监听器，此时若无速度改写则本来就没有击退可预测。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    private void onPacketBefore(PacketEvent.Receive event) {
        if (mc.player == null) return;

        Packet<?> packet = event.getPacket();
        if (isLocalMotion(packet) || isExplosionWithKnockback(packet)) {
            velocityBeforePacket = mc.player.getDeltaMovement();
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private void onPacketAfter(PacketEvent.Receive event) {
        if (mc.player == null || mc.level == null) return;

        Packet<?> packet = event.getPacket();
        Vec3 predictedVelocity = null;

        if (isLocalMotion(packet)) {
            ClientboundSetEntityMotionPacket motionPacket = (ClientboundSetEntityMotionPacket) packet;
            predictedVelocity = motionPacket.movement();
        } else if (includeExplosions.getValue() && packet instanceof ClientboundExplodePacket explosionPacket
                && explosionPacket.playerKnockback().isPresent()) {
            // 爆炸击退是速度增量，叠加到击退前速度
            predictedVelocity = velocityBeforePacket.add(explosionPacket.playerKnockback().get());
        }

        if (predictedVelocity == null || predictedVelocity.lengthSqr() < MOMENTUM_EPSILON_SQUARED) return;

        prediction = KnockbackPredictor.predictCurrentInput(mc.player, mc.level, predictedVelocity, predictTicks.getValue());
        remainingDisplayTicks = displayTicks.getValue();
        if (prediction.voidDanger()) warnAboutVoid(prediction);
    }

    @EventHandler
    private void onClientTick(ClientTickEvent.Pre event) {
        if (mc.player == null || mc.level == null) {
            clearPrediction();
            return;
        }

        if (remainingDisplayTicks > 0 && --remainingDisplayTicks == 0) {
            prediction = null;
        }
    }

    @EventHandler
    private void onLevelUpdate(LevelUpdateEvent event) {
        clearPrediction();
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (prediction == null || remainingDisplayTicks <= 0 || nullCheck()) return;

        Render3DScheduler scheduler = Render3DScheduler.INSTANCE;
        if (showPath.getValue() && prediction.path().size() >= 2) {
            List<Vec3> path = prediction.path();
            Color color = withAlpha(pathColor.getValue(), 210);
            float width = lineWidth.getValue().floatValue();
            for (int i = 1; i < path.size(); i++) {
                scheduler.addLine(path.get(i - 1), path.get(i), color, width);
            }
        }

        if (showLandingBox.getValue() && prediction.landingBox() != null) {
            Color base = landingColor.getValue();
            scheduler.addFilledBox(prediction.landingBox(), withAlpha(base, 58));
            scheduler.addOutlineBox(prediction.landingBox(), withAlpha(base, 235), lineWidth.getValue().floatValue());
        }
    }

    private void warnAboutVoid(KnockbackPredictor.Result result) {
        if (!warnVoid.getValue()) return;

        long now = System.currentTimeMillis();
        if (now - lastVoidWarningMillis < 1_000L) return;
        lastVoidWarningMillis = now;

        ChatUtils.addChatMessage(false, "§b[" + getTranslatedName() + "]§c 预测击退轨迹将进入虚空§7（" + result.simulatedTicks() + " ticks）");
    }

    private boolean isLocalMotion(Packet<?> packet) {
        return packet instanceof ClientboundSetEntityMotionPacket motionPacket
                && mc.player != null
                && motionPacket.id() == mc.player.getId();
    }

    private boolean isExplosionWithKnockback(Packet<?> packet) {
        return includeExplosions.getValue()
                && packet instanceof ClientboundExplodePacket explosionPacket
                && explosionPacket.playerKnockback().isPresent();
    }

    private void clearPrediction() {
        prediction = null;
        remainingDisplayTicks = 0;
        velocityBeforePacket = Vec3.ZERO;
    }

    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

}
