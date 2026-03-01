package com.agodasi.autocobble;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.mojang.logging.LogUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

/**
 * クライアント側イベント処理クラス。
 * Tickイベントでキー入力を監視し、ON/OFF状態を切り替える。
 * 自動化ON時は周囲のポケモンを検知し、最も近いポケモン情報を表示し続ける。
 */
@OnlyIn(Dist.CLIENT)
public class ClientEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 自動化ロジックの有効/無効状態 */
    private static boolean enabled = false;

    /** ポケモン検知用ティックカウンター */
    private static int tickCounter = 0;

    /** ポケモン検知の実行間隔（ティック数）。20ティック ≈ 1秒 */
    private static final int SCAN_INTERVAL = 20;

    /** ポケモン検知の範囲（ブロック数） */
    private static final double SCAN_RADIUS = 30.0;

    /**
     * 自動化ロジックが有効かどうかを返す。
     *
     * @return 有効なら true
     */
    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * クライアントTickイベントハンドラ。
     * 毎Tick呼ばれ、以下を行う:
     * 1) トグルキー(P)の押下チェック → ON/OFF切り替え + チャット通知
     * 2) ONの場合、20tick毎にポケモン検知を実行
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        // END phaseのみ処理（二重実行防止）
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;

        // プレイヤーがnull（メニュー画面等）なら何もしない
        if (player == null) {
            return;
        }

        // ──────────────────────────────────
        // 1) トグルキーの押下チェック
        // ──────────────────────────────────
        while (KeyBindings.TOGGLE_KEY.consumeClick()) {
            enabled = !enabled;
            tickCounter = 0;

            String status = enabled ? "§a有効" : "§c無効";
            player.displayClientMessage(
                    Objects.requireNonNull(Component.literal("[AutoCobble] " + status)),
                    false);
            LOGGER.info("[AutoCobble] toggled: {}", enabled ? "ON" : "OFF");
        }

        // ──────────────────────────────────
        // 2) 自動化ON時: ポケモン検知
        // ──────────────────────────────────
        if (enabled) {
            tickCounter++;
            if (tickCounter >= SCAN_INTERVAL) {
                tickCounter = 0;
                scanForPokemon(player);
            }
        }
    }

    /**
     * 周囲のポケモンを検知し、最も近いポケモンの情報をチャット欄に表示する。
     *
     * @param player クライアントプレイヤー
     */
    private static void scanForPokemon(LocalPlayer player) {
        if (player.level() == null) {
            return;
        }

        // プレイヤーの現在座標を中心に半径 SCAN_RADIUS のAABBを生成
        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();
        AABB scanArea = new AABB(
                px - SCAN_RADIUS, py - SCAN_RADIUS, pz - SCAN_RADIUS,
                px + SCAN_RADIUS, py + SCAN_RADIUS, pz + SCAN_RADIUS);

        // AABB内のPokemonEntityを全て取得
        List<PokemonEntity> nearbyPokemon = player.level().getEntitiesOfClass(
                PokemonEntity.class,
                scanArea);

        if (nearbyPokemon.isEmpty()) {
            return;
        }

        // プレイヤーから最も近いポケモンを取得
        PokemonEntity closest = nearbyPokemon.stream()
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(player)))
                .orElse(null);

        if (closest == null) {
            return;
        }

        // ポケモンの名前と座標をチャット欄に表示し続ける
        String name = closest.getDisplayName().getString();
        int x = (int) closest.getX();
        int y = (int) closest.getY();
        int z = (int) closest.getZ();

        player.displayClientMessage(
                Objects.requireNonNull(Component.literal(
                        Objects.requireNonNull(
                                String.format("§eTarget Found: §b%s §eat X:%d Y:%d Z:%d", name, x, y, z)))),
                false);
    }
}
