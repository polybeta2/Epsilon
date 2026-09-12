package com.github.epsilon.modules.impl.combat;

import com.github.epsilon.events.bus.EventHandler;
import com.github.epsilon.events.impl.GameLeftEvent;
import com.github.epsilon.events.impl.LevelUpdateEvent;
import com.github.epsilon.events.impl.PacketEvent;
import com.github.epsilon.events.impl.PlayerTickEvent;
import com.github.epsilon.modules.Category;
import com.github.epsilon.modules.Module;
import com.github.epsilon.settings.impl.EnumSetting;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AntiBot extends Module {

    public static final AntiBot INSTANCE = new AntiBot();

    private enum Mode {
        Tab,
        Matrix
    }

    private final EnumSetting<Mode> mode = enumSetting("Mode", Mode.Tab, m -> resetMatrixState());

    private final Set<UUID> suspectList = ConcurrentHashMap.newKeySet();
    private final Set<UUID> botList = ConcurrentHashMap.newKeySet();
    /** 等待一 tick 后再比对盔甲，用于识破 Matrix “先随机盔甲、再静默换装” 的伪装 */
    private final Map<UUID, List<ItemStack>> pendingArmor = new HashMap<>();
    /** 收到过属性更新包（UpdateAttributes）的实体 ID，用于 Attributes 异常检测 */
    private final Set<Integer> attributesSet = ConcurrentHashMap.newKeySet();

    private AntiBot() {
        super("Anti Bot", Category.COMBAT);
    }

    public boolean isBot(Entity entity) {
        if (!isEnabled()) {
            return false;
        }
        if (mode.is(Mode.Matrix)) {
            return isMatrixBot(entity);
        }
        return !mc.getConnection().getOnlinePlayerIds().contains(entity.getUUID());
    }

    private boolean isMatrixBot(Entity entity) {
        if (!(entity instanceof Player player) || mc.player == null) {
            return false;
        }
        UUID uuid = player.getUUID();
        if (botList.contains(uuid)) {
            return true;
        }

        ClientPacketListener connection = mc.getConnection();
        if (connection == null) {
            return false;
        }

        // Duplicate：Tab 中同名但 UUID 不同的条目恰好一个
        String name = player.getGameProfile().name();
        long duplicates = 0;
        for (PlayerInfo info : connection.getOnlinePlayers()) {
            GameProfile profile = info.getProfile();
            if (profile.name().equals(name) && !profile.id().equals(uuid)) {
                duplicates++;
            }
        }
        if (duplicates == 1) {
            return true;
        }

        // NoGameMode / LiteralNPC：不在 Tab 列表；26.2 的 PlayerInfo 恒有游戏模式，两个 1.8.9 检查在此合并
        if (connection.getPlayerInfo(uuid) == null) {
            return true;
        }

        // IllegalPitch
        if (Math.abs(player.getXRot()) > 90.0f) {
            return true;
        }

        // FakeEntityID：实体 ID 不在 0..1_000_000_000
        int entityId = player.getId();
        if (entityId < 0 || entityId > 1_000_000_000) {
            return true;
        }

        // IllegalHealth：血量高于本机玩家最大血量
        if (player.getHealth() > mc.player.getMaxHealth()) {
            return true;
        }

        // Attributes：从未收到属性更新包
        if (!attributesSet.contains(entityId)) {
            return true;
        }

        // IllegalScale：站立宽高与正常玩家不符；用 STANDING 而非当前姿势，避免潜行改身高误报
        EntityDimensions dimensions = player.getDimensions(Pose.STANDING);
        if (dimensions.width() != 0.6f || dimensions.height() != 1.8f) {
            return true;
        }

        return false;
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.player == null || mc.level == null) {
            return;
        }
        if (!mode.is(Mode.Matrix)) {
            return;
        }

        if (event.getPacket() instanceof ClientboundPlayerInfoUpdatePacket packet) {
            if (!packet.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER)) {
                return;
            }
            // 事件回调运行在网络线程，切回主线程再读 Tab 信息并操作集合
            mc.execute(() -> {
                for (ClientboundPlayerInfoUpdatePacket.Entry entry : packet.entries()) {
                    GameProfile profile = entry.profile();
                    if (profile == null) {
                        continue;
                    }
                    // 低延迟、带皮肤属性或名称唯一 → 非 Bot 跳过
                    if (entry.latency() < 2 || !profile.properties().isEmpty() || isGameProfileUnique(profile)) {
                        continue;
                    }
                    // 名称重复但 ID 不同 → 直接判定 Bot
                    if (isADuplicate(profile)) {
                        botList.add(profile.id());
                        continue;
                    }
                    suspectList.add(profile.id());
                }
            });
        } else if (event.getPacket() instanceof ClientboundPlayerInfoRemovePacket packet) {
            mc.execute(() -> {
                for (UUID uuid : packet.profileIds()) {
                    suspectList.remove(uuid);
                    botList.remove(uuid);
                    pendingArmor.remove(uuid);
                }
            });
        } else if (event.getPacket() instanceof ClientboundUpdateAttributesPacket packet) {
            attributesSet.add(packet.getEntityId());
        } else if (event.getPacket() instanceof ClientboundRemoveEntitiesPacket packet) {
            for (int id : packet.getEntityIds()) {
                attributesSet.remove(id);
            }
        }
    }

    @EventHandler
    private void onUpdate(PlayerTickEvent.Post event) {
        if (nullCheck() || !mode.is(Mode.Matrix)) {
            return;
        }
        if (suspectList.isEmpty() && pendingArmor.isEmpty()) {
            return;
        }

        // 处理上一 tick 捕获的待比对盔甲
        if (!pendingArmor.isEmpty()) {
            Map<UUID, List<ItemStack>> deferred = new HashMap<>(pendingArmor);
            pendingArmor.clear();
            for (Map.Entry<UUID, List<ItemStack>> entry : deferred.entrySet()) {
                UUID uuid = entry.getKey();
                Player player = mc.level.getPlayerByUUID(uuid);
                if (player == null) {
                    suspectList.remove(uuid);
                    continue;
                }
                // 盔甲发生了静默替换且无皮肤属性 → Bot
                if (!entry.getValue().equals(currentArmor(player)) && player.getGameProfile().properties().isEmpty()) {
                    botList.add(uuid);
                }
                suspectList.remove(uuid);
            }
        }

        for (Entity entity : mc.level.players()) {
            if (!(entity instanceof Player player)) {
                continue;
            }
            UUID uuid = player.getUUID();
            if (!suspectList.contains(uuid)) {
                continue;
            }

            if (isFullyArmored(player)) {
                if (player.getGameProfile().properties().isEmpty()) {
                    botList.add(uuid);
                }
                suspectList.remove(uuid);
            } else {
                // 未满装 → 记录当前盔甲，留到下一 tick 比对
                pendingArmor.put(uuid, currentArmor(player));
            }
        }
    }

    @EventHandler
    private void onLevelUpdate(LevelUpdateEvent event) {
        resetMatrixState();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        resetMatrixState();
    }

    @Override
    protected void onDisable() {
        resetMatrixState();
    }

    private boolean isFullyArmored(Player player) {
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD}) {
            ItemStack stack = player.getItemBySlot(slot);
            Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
            if (equippable == null || equippable.slot() != slot || !stack.isEnchanted()) {
                return false;
            }
        }
        return true;
    }

    private List<ItemStack> currentArmor(Player player) {
        return List.of(
                player.getItemBySlot(EquipmentSlot.FEET),
                player.getItemBySlot(EquipmentSlot.LEGS),
                player.getItemBySlot(EquipmentSlot.CHEST),
                player.getItemBySlot(EquipmentSlot.HEAD)
        );
    }

    private boolean isADuplicate(GameProfile profile) {
        ClientPacketListener connection = mc.getConnection();
        if (connection == null) {
            return false;
        }
        long count = 0;
        for (PlayerInfo info : connection.getOnlinePlayers()) {
            GameProfile other = info.getProfile();
            if (other.name().equals(profile.name()) && !other.id().equals(profile.id())) {
                count++;
            }
        }
        return count == 1;
    }

    private boolean isGameProfileUnique(GameProfile profile) {
        ClientPacketListener connection = mc.getConnection();
        if (connection == null) {
            return false;
        }
        long count = 0;
        for (PlayerInfo info : connection.getOnlinePlayers()) {
            GameProfile other = info.getProfile();
            if (other.name().equals(profile.name()) && other.id().equals(profile.id())) {
                count++;
            }
        }
        return count == 1;
    }

    private void resetMatrixState() {
        suspectList.clear();
        botList.clear();
        pendingArmor.clear();
        attributesSet.clear();
    }

}
