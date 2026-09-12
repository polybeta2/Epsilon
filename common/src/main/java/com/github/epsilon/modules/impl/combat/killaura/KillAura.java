package com.github.epsilon.modules.impl.combat.killaura;

import com.github.epsilon.events.bus.EventHandler;
import com.github.epsilon.events.bus.EventBus;
import com.github.epsilon.events.bus.listeners.ConsumerListener;
import com.github.epsilon.events.impl.ClientTickEvent;
import com.github.epsilon.events.impl.PlayerTickEvent;
import com.github.epsilon.events.impl.Render3DEvent;
import com.github.epsilon.managers.rotation.RotationManager;
import com.github.epsilon.managers.target.TargetManager;
import com.github.epsilon.modules.Category;
import com.github.epsilon.modules.Module;
import com.github.epsilon.modules.impl.combat.AntiBot;
import com.github.epsilon.modules.impl.combat.Criticals;
import com.github.epsilon.modules.impl.movement.NoSlowdown;
import com.github.epsilon.modules.impl.movement.Scaffold;
import com.github.epsilon.modules.impl.movement.Velocity;
import com.github.epsilon.settings.impl.*;
import com.github.epsilon.utils.player.PlayerUtils;
import com.github.epsilon.utils.render.esp.CaptureMarkESP;
import com.github.epsilon.utils.render.esp.CircleESP;
import com.github.epsilon.utils.render.esp.DeobfESP;
import com.github.epsilon.utils.render.esp.FireflyESP;
import com.github.epsilon.utils.rotation.Priority;
import com.github.epsilon.utils.rotation.RaytraceUtils;
import com.github.epsilon.utils.rotation.Rot2f;
import com.github.epsilon.utils.rotation.RotationUtils;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.util.Mth;

import java.awt.*;
import java.util.concurrent.ThreadLocalRandom;

public class KillAura extends Module {

    public static final KillAura INSTANCE = new KillAura();

    private KillAura() {
        super("Kill Aura", Category.COMBAT);
        EventBus.INSTANCE.subscribe(new ConsumerListener<>(Render3DEvent.class, event -> {
            DeobfESP.render(
                    event.getPoseStack(),
                    deobfSize.getValue().floatValue(),
                    deobfSpins.getValue().floatValue(),
                    deobfWobble.getValue().floatValue(),
                    deobfFlyHeight.getValue().floatValue()
            );
        }));
    }

    private enum Mode {
        OnePointEight,
        OnePointNinePlus
    }

    enum TargetMode {
        Single,
        Switch
    }

    enum PriorityMode {
        None,
        Health,
        Fov,
        Range
    }

    private enum ESPMode {
        CaptureMark,
        Circle,
        Firefly,
        Deobf
    }

    private enum NoDoubleHitMode {
        Cancel,
        NextHit,
        None
    }

    enum AutoBlockMode {
        None,
        Matrix,
        Matrix1_12
    }

    private final BoolSetting pauseOnEat = boolSetting("Pause On Eat", true);
    private final BoolSetting pauseOnScaffold = boolSetting("Pause On Scaffold", true);
    private final BoolSetting hitSelect = boolSetting("Hit Select", true);
    private final EnumSetting<Mode> mode = enumSetting("Mode", Mode.OnePointEight);
    final EnumSetting<TargetMode> targetMode = enumSetting("Target Mode", TargetMode.Single);
    final IntSetting switchDelay = intSetting("Switch Delay", 100, 0, 500, 1, () -> targetMode.is(TargetMode.Switch));
    final EnumSetting<PriorityMode> priorityMode = enumSetting("Priority Mode", PriorityMode.None);
    public final DoubleSetting searchRange = doubleSetting("Search Range", 4.0, 1.0, 6.0, 0.1);
    public final DoubleSetting aimRange = doubleSetting("Aim Range", 3.0, 1.0, 6.0, 0.1);
    final IntSetting fov = intSetting("FOV", 360, 10, 360, 1);
    private final IntSetting rotationSpeed = intSetting("Rotation Speed", 180, 10, 180, 10);
    private final EnumSetting<Priority> rotationPriority = enumSetting("Rotation Priority", Priority.High);
    private final IntSetting cps = intSetting("CPS", 12, 1, 20, 1, () -> mode.is(Mode.OnePointEight));
    private final IntSetting cpsJitter = intSetting("CPS Jitter", 25, 0, 100, 5, () -> mode.is(Mode.OnePointEight) && cps.getValue() > 1);
    private final EnumSetting<NoDoubleHitMode> noDoubleHit = enumSetting("No Double Hit", NoDoubleHitMode.Cancel);
    private final IntSetting hurtTime = intSetting("Hurt Time", 20, 0, 20, 1);
    final EnumSetting<AutoBlockMode> autoBlockMode = enumSetting("Auto Block", AutoBlockMode.None);
    final DoubleSetting blockRange = doubleSetting("Block Range", 4.0, 1.0, 6.0, 0.1, () -> !autoBlockMode.is(AutoBlockMode.None));
    final BoolSetting interactAutoBlock = boolSetting("Interact Auto Block", true, () -> autoBlockMode.is(AutoBlockMode.Matrix1_12));
    final BoolSetting autoBlockDebug = boolSetting("Debug", false, () -> !autoBlockMode.is(AutoBlockMode.None));

    final BoolSetting players = boolSetting("Players", true);
    final BoolSetting mobs = boolSetting("Mobs", true);
    final BoolSetting animals = boolSetting("Animals", true);
    final BoolSetting villagers = boolSetting("Villagers", false);
    final BoolSetting ambient = boolSetting("Ambient", false);
    final BoolSetting water = boolSetting("Water", false);
    final BoolSetting others = boolSetting("Others", false);
    final BoolSetting invisible = boolSetting("Invisible", true);

    private final BoolSetting swingHand = boolSetting("SwingHand", true);
    private final BoolSetting esp = boolSetting("ESP", true);
    private final EnumSetting<ESPMode> espMode = enumSetting("ESP Mode", ESPMode.Circle, esp::getValue);
    public final EnumSetting<DeobfESP.TextureMode> deobfMode = enumSetting("Deobf Mode", DeobfESP.TextureMode.Mengcha, () -> esp.getValue() && espMode.is(ESPMode.Deobf));
    private final DoubleSetting deobfSize = doubleSetting("Deobf Size", 0.75, 0.25, 2.0, 0.05, () -> esp.getValue() && espMode.is(ESPMode.Deobf));
    private final DoubleSetting deobfSpins = doubleSetting("Deobf Spins", 3.0, 0.5, 8.0, 0.25, () -> esp.getValue() && espMode.is(ESPMode.Deobf));
    private final DoubleSetting deobfWobble = doubleSetting("Deobf Wobble", 1.0, 0.0, 2.0, 0.1, () -> esp.getValue() && espMode.is(ESPMode.Deobf));
    private final DoubleSetting deobfFlyHeight = doubleSetting("Deobf Fly Height", 5.0, 1.0, 12.0, 0.5, () -> esp.getValue() && espMode.is(ESPMode.Deobf));
    private final ColorSetting espColor1 = colorSetting("ESP Main", new Color(255, 183, 197), () -> esp.getValue() && espMode.is(ESPMode.CaptureMark));
    private final ColorSetting espColor2 = colorSetting("ESP Second", new Color(255, 133, 161), () -> esp.getValue() && espMode.is(ESPMode.CaptureMark));
    private final DoubleSetting espSize = doubleSetting("ESP Size", 1.2, 0.5, 3.0, 0.1, () -> esp.getValue() && espMode.is(ESPMode.CaptureMark));
    private final DoubleSetting espRotSpeed = doubleSetting("Rot Speed", 2.0, 0.5, 10.0, 0.1, () -> esp.getValue() && espMode.is(ESPMode.CaptureMark));
    private final DoubleSetting waveSpeed = doubleSetting("Wave Speed", 3.0, 0.5, 10.0, 0.1, () -> esp.getValue() && espMode.is(ESPMode.CaptureMark));
    private final ColorSetting sideColor = colorSetting("Side Color", Color.WHITE, false, () -> esp.getValue() && espMode.is(ESPMode.Circle));
    private final ColorSetting lineColor = colorSetting("Line Color", new Color(255, 255, 255, 233), () -> esp.getValue() && espMode.is(ESPMode.Circle));
    private final DoubleSetting circleRadius = doubleSetting("Circle Radius", 0.75, 0.1, 2.0, 0.05, () -> esp.getValue() && espMode.is(ESPMode.Circle));
    private final DoubleSetting circleAlphaFactor = doubleSetting("Circle Alpha Factor", 1.0, 0.0, 2.0, 0.05, () -> esp.getValue() && espMode.is(ESPMode.Circle));
    private final EnumSetting<FireflyESP.ColorMode> fireflyColorMode = enumSetting("Firefly Color Mode", FireflyESP.ColorMode.Blend, () -> esp.getValue() && espMode.is(ESPMode.Firefly));
    private final ColorSetting fireflyColor = colorSetting("Firefly Color", new Color(149, 149, 149, 255), () -> esp.getValue() && espMode.is(ESPMode.Firefly));
    private final ColorSetting fireflyColor2 = colorSetting("Firefly Color 2", new Color(255, 133, 161, 255), () -> esp.getValue() && espMode.is(ESPMode.Firefly) && fireflyColorMode.is(FireflyESP.ColorMode.Blend));
    private final DoubleSetting fireflyColorMix = doubleSetting("Firefly Color Mix", 0.65, 0.0, 1.0, 0.05, () -> esp.getValue() && espMode.is(ESPMode.Firefly) && fireflyColorMode.is(FireflyESP.ColorMode.Blend));
    private final DoubleSetting fireflyColorSpeed = doubleSetting("Firefly Color Speed", 1.2, 0.1, 6.0, 0.1, () -> esp.getValue() && espMode.is(ESPMode.Firefly) && fireflyColorMode.is(FireflyESP.ColorMode.Blend));
    private final DoubleSetting fireflyRainbowSpeed = doubleSetting("Firefly Rainbow Speed", 1.0, 0.1, 6.0, 0.1, () -> esp.getValue() && espMode.is(ESPMode.Firefly) && fireflyColorMode.is(FireflyESP.ColorMode.Rainbow));
    private final DoubleSetting fireflyRainbowSaturation = doubleSetting("Firefly Rainbow Saturation", 0.85, 0.1, 1.0, 0.05, () -> esp.getValue() && espMode.is(ESPMode.Firefly) && fireflyColorMode.is(FireflyESP.ColorMode.Rainbow));
    private final DoubleSetting fireflyRainbowBrightness = doubleSetting("Firefly Rainbow Brightness", 1.0, 0.1, 1.0, 0.05, () -> esp.getValue() && espMode.is(ESPMode.Firefly) && fireflyColorMode.is(FireflyESP.ColorMode.Rainbow));
    private final IntSetting fireflyLength = intSetting("Firefly Length", 14, 8, 128, 1, () -> esp.getValue() && espMode.is(ESPMode.Firefly));
    private final IntSetting fireflyFactor = intSetting("Firefly Factor", 8, 1, 10, 1, () -> esp.getValue() && espMode.is(ESPMode.Firefly));
    private final DoubleSetting fireflyShaking = doubleSetting("Firefly Shaking", 1.8, 0.25, 10.0, 0.25, () -> esp.getValue() && espMode.is(ESPMode.Firefly));
    private final DoubleSetting fireflyAmplitude = doubleSetting("Firefly Amplitude", 3.0, 0.0, 10.0, 0.25, () -> esp.getValue() && espMode.is(ESPMode.Firefly));

    public LivingEntity target;

    private int attacks;
    private long lastAttackTime;

    private final KillAuraTargeting targeting = new KillAuraTargeting();
    private final KillAuraAutoBlock autoBlock = new KillAuraAutoBlock(this);

    @Override
    public String getInfo() {
        return target == null ? null : target.getName().getString();
    }

    @Override
    protected void onDisable() {
        resetState();
        DeobfESP.retainRisingEffects();
    }

    @EventHandler
    private void onClientTick(ClientTickEvent.Pre event) {
        if (nullCheck()) return;

        if (!esp.getValue() || !espMode.is(ESPMode.Deobf)) {
            DeobfESP.clear();
        }

        if (pauseOnScaffold.getValue() && Scaffold.INSTANCE.isEnabled()) {
            target = null;
            autoBlock.reset();
            return;
        }

        target = targeting.select(this);

        autoBlock.tick(target);

        if (target == null) return;

        Rot2f calculate = RotationUtils.calculate(target, true, aimRange.getValue());
        if (RaytraceUtils.raytrace(calculate, aimRange.getValue()).getType() == HitResult.Type.BLOCK) return;
        RotationManager.INSTANCE.setRotations(calculate, rotationSpeed.getValue(), rotation -> RaytraceUtils.raytrace(rotation, 3.0f) instanceof EntityHitResult entityHitResult && entityHitResult.getEntity() == target, rotationPriority.getValue());

        HitResult hitResult = RotationManager.INSTANCE.getHitResult();
        if (hitSelect.getValue() && hitResult instanceof EntityHitResult entityHitResult && entityHitResult.getEntity() instanceof Player player && !AntiBot.INSTANCE.isBot(player) && !TargetManager.INSTANCE.isSameTeam(player) && Velocity.INSTANCE.attackQueue <= 0) {
            ClientPacketListener connection = mc.getConnection();
            PlayerInfo localPlayerInfo = connection == null ? null : connection.getPlayerInfo(mc.player.getUUID());
            int latencyTicks = localPlayerInfo == null ? 0 : localPlayerInfo.getLatency() / 50;
            if (player.hurtTime <= latencyTicks + 1 || (mc.player.hurtTime >= 6 && !Velocity.INSTANCE.isEnabled()) || Criticals.INSTANCE.fallTicks == 2) {
                accumulateAttackBudget();
            }
        }
    }

    @EventHandler
    private void onPlayerTick(PlayerTickEvent.Pre event) {
        // Cancel：本轮无论积攒了多少预算，最多只打出一击
        if (noDoubleHit.is(NoDoubleHitMode.Cancel)) {
            attacks = Math.min(1, attacks);
        }
        HitResult hitResult = RotationManager.INSTANCE.getHitResult();
        while (attacks > 0) {
            attacks--;
            if (pauseOnEat.getValue() && PlayerUtils.isEating() || NoSlowdown.INSTANCE.isWorking()) return;
            if (hitResult instanceof EntityHitResult entityHitResult) {
                Entity entity = entityHitResult.getEntity();
                if (!entity.isAlive()) return;

                // 目标处于受击无敌帧时不出手，把预算留在下一 tick 等待窗口结束
                if (entity instanceof LivingEntity living && living.hurtTime > hurtTime.getValue()) {
                    attacks++;
                    break;
                }

                // Matrix1.12：释放盾并跳过本次攻击，下一 tick 再打（两 tick 状态机）
                if (autoBlock.skipTickForMatrix112()) {
                    attacks++;
                    break;
                }

                // Matrix AutoBlock：释放盾 → 攻击 → 补盾，攻击到达服务端时不处于格挡状态
                boolean wasBlocking = autoBlock.beginAttack();
                mc.gameMode.attack(mc.player, entity);
                autoBlock.endAttack(wasBlocking);
                autoBlock.postAttack(entity);

                if (espMode.is(ESPMode.Deobf)) DeobfESP.markHit(entity);

                if (swingHand.getValue()) {
                    mc.player.swing(InteractionHand.MAIN_HAND);
                } else {
                    mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
                }

                // NextHit：命中后结束本轮消费，剩余预算留到下一 tick
                if (noDoubleHit.is(NoDoubleHitMode.NextHit)) break;
            }
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (target != null && Velocity.INSTANCE.attackQueue <= 0) {
            HitResult hitResult = RotationManager.INSTANCE.getHitResult();
            if (!hitSelect.getValue() || !(hitResult instanceof EntityHitResult entityHitResult && entityHitResult.getEntity() instanceof Player)) {
                accumulateAttackBudget();
            }
        }

        if (!esp.getValue() || espMode.is(ESPMode.Deobf) || target == null) return;

        PoseStack stack = event.getPoseStack();

        switch (espMode.getValue()) {
            case CaptureMark -> {
                CaptureMarkESP.render(
                        stack,
                        target,
                        espSize.getValue(),
                        espRotSpeed.getValue(),
                        waveSpeed.getValue(),
                        espColor1.getValue(),
                        espColor2.getValue()
                );
            }
            case Circle -> {
                CircleESP.render(
                        stack,
                        target,
                        circleRadius.getValue().floatValue(),
                        sideColor.getValue(),
                        lineColor.getValue(),
                        circleAlphaFactor.getValue().floatValue()
                );
            }
            case Firefly -> {
                FireflyESP.render(
                        stack,
                        target,
                        fireflyLength.getValue(),
                        fireflyFactor.getValue(),
                        fireflyShaking.getValue(),
                        fireflyAmplitude.getValue(),
                        fireflyColor.getValue(),
                        fireflyColorMode.getValue(),
                        fireflyColor2.getValue(),
                        fireflyColorMix.getValue(),
                        fireflyColorSpeed.getValue(),
                        fireflyRainbowSpeed.getValue(),
                        fireflyRainbowSaturation.getValue(),
                        fireflyRainbowBrightness.getValue()
                );
            }
        }
    }

    private void accumulateAttackBudget() {
        switch (mode.getValue()) {
            case OnePointNinePlus -> {
                if (attacks == 0 && mc.player.getAttackStrengthScale(0.5f) >= 1.0f) {
                    attacks++;
                }
            }
            case OnePointEight -> {
                long time = System.currentTimeMillis();
                // 每次评估在基础间隔上叠加 ±Jitter% 的随机偏移，避免攻击节奏收敛为稳定值
                long interval = (long) (1000.0 / cps.getValue());
                double jitter = 1.0 + ThreadLocalRandom.current().nextInt(-cpsJitter.getValue(), cpsJitter.getValue() + 1) / 100.0;
                if (time - lastAttackTime >= (long) (interval * jitter)) {
                    attacks++;
                    lastAttackTime = time;
                }
            }
        }
    }

    /**
     * AutoBlock 当前是否让服务端认为玩家处于格挡状态；供减速链路套用 1.8 格挡减速。
     */
    public boolean isBlockingServerSide() {
        return autoBlock.isServerBlocking();
    }

    private void resetState() {
        target = null;
        attacks = 0;
        lastAttackTime = 0L;
        autoBlock.reset();
    }

}
