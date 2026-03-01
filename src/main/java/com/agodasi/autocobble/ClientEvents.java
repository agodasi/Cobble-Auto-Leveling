package com.agodasi.autocobble;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * クライアント側イベント処理クラス。
 * Tickイベントでキー入力を監視し、ON/OFF状態を切り替える。
 * 自動化ON時は周囲のポケモンを検知する。
 */
@OnlyIn(Dist.CLIENT)
public class ClientEvents {

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
     * トグルキーが押された時にON/OFFを切り替え、チャット欄にメッセージを表示する。
     * 自動化ON時は一定間隔で周囲のポケモンを検知する。
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

        // トグルキーの押下チェック
        while (KeyBindings.TOGGLE_KEY.consumeClick()) {
            enabled = !enabled;
            tickCounter = 0;

            // チャット欄にシステムメッセージとして状態を表示
            String status = enabled ? "§a有効" : "§c無効";
            player.displayClientMessage(
                    Objects.requireNonNull(Component.literal("[AutoCobble] " + status)),
                    false);
        }

        // 自動化がONの場合、一定間隔でポケモンを検知する
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

        // プレイヤーの現在座標を中心にAABBを生成
        AABB scanArea = new AABB(
                player.getX() - SCAN_RADIUS, player.getY() - SCAN_RADIUS, player.getZ() - SCAN_RADIUS,
                player.getX() + SCAN_RADIUS, player.getY() + SCAN_RADIUS, player.getZ() + SCAN_RADIUS);

        // AABB内のPokemonEntityを取得
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

        // ポケモンの名前と座標をチャット欄に表示
        String name = closest.getDisplayName().getString();
        int x = (int) closest.getX();
        int y = (int) closest.getY();
        int z = (int) closest.getZ();

        player.displayClientMessage(
                Objects.requireNonNull(
                        Component.literal(Objects.requireNonNull(
                                String.format("§eTarget Found: §b%s §eat X:%d Y:%d Z:%d", name, x, y, z)))),
                false);
    }
}
