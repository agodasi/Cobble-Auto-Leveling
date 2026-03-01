package com.agodasi.autocobble;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nonnull;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.mojang.logging.LogUtils;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

/**
 * クライアント側イベント処理クラス。
 * Tickイベントでキー入力を監視し、ON/OFF状態を切り替える。
 * 自動化ON時は周囲のポケモンを検知し、自動的に近づく。
 */
@OnlyIn(Dist.CLIENT)
public class ClientEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 自動化ロジックの有効/無効状態 */
    private static boolean enabled = false;

    /** ポケモン検知用ティックカウンター */
    private static int scanTickCounter = 0;

    /** ポケモン検知の実行間隔（ティック数）。20ティック ≈ 1秒 */
    private static final int SCAN_INTERVAL = 20;

    /** ポケモン検知の範囲（ブロック数） */
    private static final double SCAN_RADIUS = 30.0;

    /** ポケモンに十分近いとみなす距離（ブロック数） */
    private static final double ARRIVE_DISTANCE = 1.0;

    /** 現在追跡中のポケモン */
    private static PokemonEntity targetPokemon = null;

    /** チャット表示用ティックカウンター（スパム防止） */
    private static int chatTickCounter = 0;
    private static final int CHAT_INTERVAL = 40;

    /** アクションのクールダウンカウンター */
    private static int actionCooldown = 0;

    /** 押下状態にしているCobblemonのキー */
    private static KeyMapping pressedKey = null;

    /** キーを離すまでの残りTick数 */
    private static int releaseKeyTick = 0;

    /**
     * 自動化ロジックが有効かどうかを返す。
     */
    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * クライアントTickイベントハンドラ。
     * 毎Tick呼ばれ、以下を行う:
     * 1) トグルキー(P)の押下チェック → ON/OFF切り替え + チャット通知
     * 2) ONの場合、定期的にポケモン検知 + 毎Tickポケモンに向かって移動
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;

        if (player == null) {
            return;
        }

        // クールダウンとキーの解放処理
        if (actionCooldown > 0) {
            actionCooldown--;
        }

        if (releaseKeyTick > 0) {
            releaseKeyTick--;
            if (releaseKeyTick == 0 && pressedKey != null) {
                pressedKey.setDown(false);
                pressedKey = null;
            }
        }

        // ──────────────────────────────────
        // 1) トグルキーの押下チェック
        // ──────────────────────────────────
        while (KeyBindings.TOGGLE_KEY.consumeClick()) {
            enabled = !enabled;
            scanTickCounter = 0;
            chatTickCounter = 0;

            if (!enabled) {
                // OFFにした場合、ターゲットをクリアして移動を停止
                targetPokemon = null;
                mc.options.keyUp.setDown(false);
                mc.options.keySprint.setDown(false);

                // Baritoneが利用可能ならキャンセル
                try {
                    if (BaritoneHelper.isAvailable()) {
                        BaritoneHelper.cancelMovement();
                    }
                } catch (Throwable e) {
                    // Baritone未導入時は無視
                }
            }

            String status = enabled ? "§a有効" : "§c無効";
            player.displayClientMessage(
                    Objects.requireNonNull(Component.literal("[AutoCobble] " + status)),
                    false);
            LOGGER.info("[AutoCobble] toggled: {}", enabled ? "ON" : "OFF");
        }

        // ──────────────────────────────────
        // 2) 自動化ON時: ポケモン検知 & 移動
        // ──────────────────────────────────
        if (!enabled) {
            return;
        }

        // 定期的にポケモンをスキャンしてターゲットを更新
        scanTickCounter++;
        if (scanTickCounter >= SCAN_INTERVAL) {
            scanTickCounter = 0;
            updateTarget(player);
        }

        // ターゲットが無効になった場合はクリア
        if (targetPokemon != null && (!targetPokemon.isAlive() || targetPokemon.isRemoved())) {
            targetPokemon = null;
        }

        // ターゲットがいる場合、毎Tickポケモンに向かって移動
        if (targetPokemon != null) {
            moveTowardTarget(mc, player, targetPokemon);
        } else {
            // ターゲットがいない場合は移動停止
            mc.options.keyUp.setDown(false);
            mc.options.keySprint.setDown(false);
        }
    }

    /**
     * 周囲のポケモンをスキャンし、最も近いポケモンをターゲットに設定する。
     */
    private static void updateTarget(LocalPlayer player) {
        if (player.level() == null) {
            return;
        }

        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();
        AABB scanArea = new AABB(
                px - SCAN_RADIUS, py - SCAN_RADIUS, pz - SCAN_RADIUS,
                px + SCAN_RADIUS, py + SCAN_RADIUS, pz + SCAN_RADIUS);

        List<PokemonEntity> nearbyPokemon = player.level().getEntitiesOfClass(
                PokemonEntity.class,
                scanArea);

        if (nearbyPokemon.isEmpty()) {
            targetPokemon = null;
            return;
        }

        // 最も近いポケモンをターゲットに設定
        PokemonEntity closest = nearbyPokemon.stream()
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(player)))
                .orElse(null);

        if (closest != null) {
            targetPokemon = closest;

            // チャット通知（スパム防止）
            chatTickCounter++;
            if (chatTickCounter >= CHAT_INTERVAL / SCAN_INTERVAL) {
                chatTickCounter = 0;
                String name = closest.getDisplayName().getString();
                int x = (int) closest.getX();
                int y = (int) closest.getY();
                int z = (int) closest.getZ();
                double dist = Math.sqrt(closest.distanceToSqr(player));

                player.displayClientMessage(
                        Objects.requireNonNull(Component.literal(
                                Objects.requireNonNull(String.format("§eTarget: §b%s §e(%.1fブロック先) X:%d Y:%d Z:%d",
                                        name, dist, x, y, z)))),
                        true); // trueでアクションバーに表示（チャット欄を汚さない）
            }
        }
    }

    /**
     * ターゲットポケモンに向かってプレイヤーを移動させる。
     * プレイヤーの視線をポケモンに向け、前進キーを押し続ける。
     */
    private static void moveTowardTarget(Minecraft mc, LocalPlayer player, @Nonnull Entity target) {
        double dist = player.distanceTo(target);

        // 1.0ブロック以下の場合は到達と判定し、エイム＆インタラクトを実行
        if (dist <= 1.0) {
            mc.options.keyUp.setDown(false);
            mc.options.keySprint.setDown(false);
            mc.options.keyJump.setDown(false);

            if (actionCooldown == 0) {
                // 【エイムの厳密な計算と適用】
                // プレイヤーの目の高さの座標とポケモンの中心座標
                Vec3 eyePos = player.getEyePosition();
                double targetX = target.getX();
                double targetY = target.getY() + target.getBbHeight() / 2.0;
                double targetZ = target.getZ();

                double dxAim = targetX - eyePos.x;
                double dyAim = targetY - eyePos.y;
                double dzAim = targetZ - eyePos.z;
                double horizontalAimDist = Math.sqrt(dxAim * dxAim + dzAim * dzAim);

                // Mth.atan2 を用いて Yaw と Pitch を数学的に計算
                float targetYaw = (float) (Math.atan2(dzAim, dxAim) * (180.0 / Math.PI)) - 90.0F;
                float targetPitch = (float) -(Math.atan2(dyAim, horizontalAimDist) * (180.0 / Math.PI));

                // 視点の即座適用
                player.setYRot(targetYaw);
                player.setXRot(targetPitch);

                // 【キー入力のシミュレート】
                KeyMapping interactKey = null;
                for (KeyMapping key : mc.options.keyMappings) {
                    String name = key.getName().toLowerCase();
                    String category = key.getCategory().toLowerCase();

                    // Cobblemonのキー（特に send_out や interact）を探す
                    if (category.contains("cobblemon") || name.contains("cobblemon")) {
                        if (name.contains("send_out") || name.contains("interact") || name.contains("pokemon")) {
                            if (name.contains("send_out")) {
                                interactKey = key;
                                break;
                            }
                            if (interactKey == null) {
                                interactKey = key;
                            }
                        }
                    }
                }

                // 見つからなかった場合のフォールバック検索
                if (interactKey == null) {
                    for (KeyMapping key : mc.options.keyMappings) {
                        if (key.getName().equals("key.cobblemon.send_out")
                                || key.getName().equals("cobblemon.key.send_out")) {
                            interactKey = key;
                            break;
                        }
                    }
                }

                if (interactKey != null) {
                    // キー押下状態をシミュレートし、数Tick後に離す
                    interactKey.setDown(true);
                    pressedKey = interactKey;
                    releaseKeyTick = 2; // 2Tick後に離す
                    LOGGER.info("[AutoCobble] Triggered interact on target: {}", target.getName().getString());
                } else {
                    LOGGER.warn("[AutoCobble] Cobblemon send_out KeyMapping not found, cannot interact!");
                }

                // 60Tick（約3秒）のクールダウン
                actionCooldown = 60;
            }
            return;
        }

        double dx = target.getX() - player.getX();
        double dy = (target.getY() + target.getEyeHeight() / 2.0) - (player.getY() + player.getEyeHeight());
        double dz = target.getZ() - player.getZ();
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);

        // 念のため、既存の距離判定による移動停止（ARRIVE_DISTANCE = 1.0 に変更済み）
        if (horizontalDist < ARRIVE_DISTANCE) {
            mc.options.keyUp.setDown(false);
            mc.options.keySprint.setDown(false);
            return;
        }

        // プレイヤーの視線をターゲットに向ける（yaw/pitch計算）
        float targetYaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        float targetPitch = (float) -(Mth.atan2(dy, horizontalDist) * (180.0 / Math.PI));

        // 急激に回転しないよう、現在の角度からスムーズに補間
        float currentYaw = player.getYRot();
        float currentPitch = player.getXRot();

        float yawDiff = Mth.wrapDegrees(targetYaw - currentYaw);
        float pitchDiff = targetPitch - currentPitch;

        // 1Tickで最大回転速度を制限（スムーズな旋回）
        float maxRotSpeed = 15.0F;
        float newYaw = currentYaw + Mth.clamp(yawDiff, -maxRotSpeed, maxRotSpeed);
        float newPitch = currentPitch + Mth.clamp(pitchDiff, -maxRotSpeed, maxRotSpeed);

        player.setYRot(newYaw);
        player.setXRot(Mth.clamp(newPitch, -80.0F, 80.0F));

        // 前進キーを押し続ける
        mc.options.keyUp.setDown(true);

        // 距離が遠い場合はスプリント
        if (horizontalDist > 8.0) {
            mc.options.keySprint.setDown(true);
        } else {
            mc.options.keySprint.setDown(false);
        }

        // ジャンプ判定：前方にブロックがある場合はジャンプ
        if (player.horizontalCollision && player.onGround()) {
            mc.options.keyJump.setDown(true);
        } else {
            mc.options.keyJump.setDown(false);
        }
    }
}
