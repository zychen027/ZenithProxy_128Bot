package com.zenith.module.impl;

import com.zenith.cache.data.PlayerCache;
import com.github.rfresh2.EventConsumer;
import com.zenith.event.client.ClientBotTick;
import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.mc.block.Direction;
import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.util.RequestFuture;
import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.item.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemOnPacket;

import java.util.List;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Globals.*;

public class AutoVault extends AbstractInventoryModule {

    // ==========================================
    // 硬编码配置项 (请根据实际情况精准修改)
    // ==========================================
    // 预定位置坐标 (Bot要去的位置，站立的脚部坐标)
    private final double TARGET_X = 0.5;
    private final double TARGET_Y = 300.0;
    private final double TARGET_Z = 0.5;

    // 预定开宝库方向 (到达预定坐标后，强制旋转至该角度与宝库交互)
    private final float TARGET_YAW = 90.0f;
    private final float TARGET_PITCH = 0.0f;

    // 宝库方块的精确坐标及交互面 (用于右键交互的数据包)
    private final Vector3i VAULT_POS = Vector3i.from(0, 300, 0);
    private final Direction VAULT_FACE = Direction.NORTH; // 交互面，如 UP, DOWN, NORTH 等
    // ==========================================

    private int cooldownTicks = 0;
    private int tickCounter = 0; // 用于控制低频日志输出
    private static final int COOLDOWN = 10;
    RequestFuture swapFuture = RequestFuture.rejected;

    public AutoVault() {
        super(HandRestriction.EITHER, 3);
    }

    @Override
    public boolean enabledSetting() {
        return CONFIG.client.extra.autoVault.enabled;
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(of(ClientBotTick.class, this::handleClientTick));
    }

    @Override
    public int getPriority() {
        return 10000;
    }

    @Override
    public boolean itemPredicate(final ItemStack itemStack) {
        ItemData itemData = ItemRegistry.REGISTRY.get(itemStack.getId());
        return itemData != null && (itemData.name().equals("minecraft:trial_key") || itemData.name().equals("minecraft:ominous_trial_key"));
    }

    @Override
    public void onEnable() {
        cooldownTicks = 0;
        tickCounter = 0;
        swapFuture = RequestFuture.rejected;
        info("AutoVault 已启用 (硬编码无误差模式 + 坐标输出)");
    }

    @Override
    public void onDisable() {
        cooldownTicks = 0;
        tickCounter = 0;
        swapFuture = RequestFuture.rejected;
        info("AutoVault 已禁用");
    }

    // 获取当前坐标的格式化字符串，方便日志输出
    private String getCurrentPosString() {
        return String.format("[当前坐标: %.2f, %.2f, %.2f]",
            CACHE.getPlayerCache().getX(),
            CACHE.getPlayerCache().getY(),
            CACHE.getPlayerCache().getZ());
    }

    public void handleClientTick(final ClientBotTick e) {
        if (cooldownTicks > 0) {
            cooldownTicks--;
            INVENTORY.submit(InventoryActionRequest.noAction(this, getPriority()));
            return;
        }

        tickCounter++;

        if (CACHE.getPlayerCache().getThePlayer() == null || !CACHE.getPlayerCache().getThePlayer().isAlive()) return;

        // 检查是否到达预定位置附近 (距离预定坐标小于2格视为已到达)
        double px = CACHE.getPlayerCache().getX();
        double py = CACHE.getPlayerCache().getY();
        double pz = CACHE.getPlayerCache().getZ();
        double distSq = (px - TARGET_X) * (px - TARGET_X) + (py - TARGET_Y) * (py - TARGET_Y) + (pz - TARGET_Z) * (pz - TARGET_Z);

        if (distSq > 4.0) {
            INVENTORY.submit(InventoryActionRequest.noAction(this, getPriority()));
            // 每2秒左右输出一次当前位置，防止日志刷屏，同时方便你确认Bot在哪
            if (tickCounter % 40 == 0) {
                info("未到达预定位置，等待中... " + getCurrentPosString());
            }
            return;
        }

        // 到达预定位置附近，开始处理物品换位
        if (!swapFuture.isDone()) {
            INVENTORY.submit(InventoryActionRequest.noAction(this, getPriority()));
            info("物品正在换位中 (SWAPPING)，等待完成... " + getCurrentPosString());
            return;
        }

        var invActionResult = doInventoryActionsV2();
        switch (invActionResult.state()) {
            case ITEM_IN_HAND -> {
                info("物品已在手中! 强制对齐到预定坐标和方向，准备交互 " + getCurrentPosString());
                interactWithVault();
                cooldownTicks = COOLDOWN;
                INVENTORY.submit(InventoryActionRequest.noAction(this, getPriority()));
            }
            case NO_ITEM -> {
                INVENTORY.submit(InventoryActionRequest.noAction(this, getPriority()));
                info("背包中没有试炼钥匙 (NO_ITEM) " + getCurrentPosString());
            }
            case SWAPPING -> {
                swapFuture = invActionResult.inventoryActionFuture();
                info("开始移动物品到主手 (SWAPPING)... " + getCurrentPosString());
            }
            default -> throw new IllegalStateException("Unexpected action state: " + invActionResult.state());
        }
    }

    private void interactWithVault() {
        // 强制修改本地缓存中的视角
        CACHE.getPlayerCache().setYaw(TARGET_YAW);
        CACHE.getPlayerCache().setPitch(TARGET_PITCH);

        // 发送精准的位置+视角包，强制服务器无误差对齐 (修正参数顺序: boolean, boolean, x, y, z, yaw, pitch)
        sendClientPacketAsync(new ServerboundMovePlayerPosRotPacket(
            true, false, TARGET_X, TARGET_Y, TARGET_Z, TARGET_YAW, TARGET_PITCH
        ));
        info(String.format("已发送强制对齐包: 目标XYZ=%.2f,%.2f,%.2f | Yaw=%.1f, Pitch=%.1f | %s",
            TARGET_X, TARGET_Y, TARGET_Z, TARGET_YAW, TARGET_PITCH, getCurrentPosString()));

        // 发送精准的交互包
        ServerboundUseItemOnPacket packet = new ServerboundUseItemOnPacket(
            VAULT_POS.getX(), VAULT_POS.getY(), VAULT_POS.getZ(), VAULT_FACE.mcpl(), Hand.MAIN_HAND, 0.5f, 0.5f, 0.5f, false, false, 0
        );
        sendClientPacketAsync(packet);
        info("已发送精准右键交互包! BlockPos=" + VAULT_POS + ", Face=" + VAULT_FACE.mcpl() + " | " + getCurrentPosString());
    }
}
