package com.agodasi.autocobble;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nonnull;

import com.cobblemon.mod.common.client.gui.battle.BattleGUI;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.mojang.logging.LogUtils;

import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleActionSelection;
import com.cobblemon.mod.common.client.battle.SingleActionRequest;
import com.cobblemon.mod.common.client.battle.ActiveClientBattlePokemon;
import com.cobblemon.mod.common.client.battle.ClientBattlePokemon;
import com.cobblemon.mod.common.client.battle.ClientBattleActor;
import com.cobblemon.mod.common.battles.ShowdownMoveset;
import com.cobblemon.mod.common.battles.InBattleMove;
import com.cobblemon.mod.common.battles.MoveActionResponse;
import com.cobblemon.mod.common.battles.SwitchActionResponse;
import com.cobblemon.mod.common.api.moves.Moves;
import com.cobblemon.mod.common.api.moves.MoveTemplate;
import com.cobblemon.mod.common.api.moves.categories.DamageCategories;
import com.cobblemon.mod.common.api.types.ElementalType;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.Cobblemon;

import com.cobblemon.mod.common.api.moves.MoveSet;
import com.cobblemon.mod.common.api.moves.Move;
import com.cobblemon.mod.common.pokemon.status.PersistentStatusContainer;
import java.util.Set;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraftforge.registries.ForgeRegistries;
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

    /** 伝説・幻・UBポケモンの名前セット（判別用・メモリ最適化） */
    private static final java.util.Set<String> LEGENDARY_POKEMON = new java.util.HashSet<>(java.util.Arrays.asList(
            // 鳥伝説
            "articuno", "zapdos", "moltres",
            // ミュウツー、ミュウ
            "mewtwo", "mew",
            // 第二世代
            "raikou", "entei", "suicune", "lugia", "ho-oh", "celebi",
            // 第三世代
            "regirock", "regice", "registeel", "latias", "latios",
            "kyogre", "groudon", "rayquaza", "jirachi", "deoxys",
            // 第四世代
            "uxie", "mesprit", "azelf", "dialga", "palkia", "heatran",
            "regigigas", "giratina", "cresselia", "phione", "manaphy", "darkrai", "shaymin", "arceus",
            // 第五世代
            "victini", "cobalion", "terrakion", "virizion", "tornadus", "thundurus", "reshiram", "zekrom",
            "landorus", "kyurem", "keldeo", "meloetta", "genesect",
            // 第六世代
            "xerneas", "yveltal", "zygarde", "diancie", "hoopa", "volcanion",
            // 第七世代
            "type: null", "silvally", "tapu koko", "tapu lele", "tapu bulu", "tapu fini",
            "cosmog", "cosmoem", "solgaleo", "lunala", "nihilego", "buzzwole", "pheromosa", "xurkitree",
            "celesteela", "kartana", "guzzlord", "necrozma", "magearna", "marshadow", "poipole", "naganadel",
            "stakataka", "blacephalon", "zeraora", "meltan", "melmetal",
            // 第八世代
            "zacian", "zamazenta", "eternatus", "kubfu", "urshifu", "zarude", "regieleki", "regidrago",
            "glastrier", "spectrier", "calyrex", "enamorus",
            // 第九世代
            "wo-chien", "chien-pao", "ting-lu", "chi-yu", "koraidon", "miraidon", "walking wake", "iron leaves",
            "okidogi", "munkidori", "fezandipiti", "ogerpon", "terapagos", "iron boulder", "iron crown",
            "gouging fire", "raging bolt", "pecharunt"));

    /** ポケモン検知用ティックカウンター */
    private static int scanTickCounter = 0;

    /** ポケモン検知の実行間隔（ティック数）。20ティック ≈ 1秒 */
    private static final int SCAN_INTERVAL = 20;

    /** ポケモン検知の範囲（ブロック数） */
    private static final double SCAN_RADIUS = 45.0;

    /** バトル進行がない時のタイムアウト（ティック数）。20ティック * 60秒 = 1200ティック */
    private static final int BATTLE_TIMEOUT_TICKS = 200;

    /** 現在バトルでの経過ティック数 */
    private static int battleStuckTickCounter = 0;

    /** 現在追跡中のポケモン */
    private static PokemonEntity targetPokemon = null;

    /** 待機/回復関連のインターバルカウンタ */
    private static int healingScanTickCounter = 0;
    private static int healingStuckTickCounter = 0;

    /** チャット表示用ティックカウンター（スパム防止） */
    private static int chatTickCounter = 0;
    private static final int CHAT_INTERVAL = 40;

    /** アクションのクールダウンカウンター */
    private static int actionCooldown = 0;

    /** 押下状態にしているCobblemonのキー */
    private static KeyMapping pressedKey = null;

    /** キーを離すまでの残りTick数 */
    private static int releaseKeyTick = 0;

    /** ターゲットの座標（目標を更新するかどうかの判定用） */
    private static double lastTargetX = 0;
    private static double lastTargetY = 0;
    private static double lastTargetZ = 0;

    /** Baritoneが経路探索中かどうかのフラグ */
    private static boolean baritonePathing = false;

    /** バトル中かどうか */
    private static boolean inBattle = false;

    /**
     * バトル終了時に回復ステートへ移行するためのフラグ
     */
    public static boolean needHealing = false;

    private static BlockPos targetHealingMachine = null;

    /** 睡眠管理用の変数 */
    private static int nightsPassedWithoutSleep = 0;
    private static long lastDayHandled = -1;
    private static boolean needSleep = false;
    private static boolean isWaitingForMorning = false;
    private static int sleepCancelTickCounter = 0;
    private static BlockPos targetBed = null;
    private static int sleepScanTickCounter = 0;

    /** 戦闘終了時のタイムスタンプ（ティック数） */
    private static long lastBattleEndTime = 0;

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

        // ──────────────────────────────────
        // 夜数カウントと睡眠判定
        // ──────────────────────────────────
        if (mc.level != null) {
            long time = mc.level.getDayTime();
            long currentDay = time / 24000;
            long timeOfDay = time % 24000;

            // 夕方（12500〜）になったらカウント判定
            if (timeOfDay >= 12500 && currentDay > lastDayHandled) {
                lastDayHandled = currentDay;
                nightsPassedWithoutSleep++;
                LOGGER.info("[AutoCobble] Night falls. Nights without sleep: {}", nightsPassedWithoutSleep);
                if (nightsPassedWithoutSleep >= 3) {
                    needSleep = true;
                    LOGGER.info("[AutoCobble] 3 nights passed. needSleep set to true.");
                }
            }

            // 朝になったら待機フラグを解除（0〜1000の間を朝と判定）
            if (isWaitingForMorning && timeOfDay >= 0 && timeOfDay < 1000) {
                isWaitingForMorning = false;
                LOGGER.info("[AutoCobble] Morning arrived. Resuming auto-exploration.");
            }
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

        if (mc.screen != null) {
            mc.options.keyUp.setDown(false);
            mc.options.keyDown.setDown(false);
            mc.options.keyLeft.setDown(false);
            mc.options.keyRight.setDown(false);
            mc.options.keyJump.setDown(false);
            mc.options.keySprint.setDown(false);

            if (mc.screen instanceof BattleGUI) {
                if (!inBattle) {
                    inBattle = true;
                    battleStuckTickCounter = 0;
                    LOGGER.info("[AutoCobble] Entered Battle Screen.");
                }

                battleStuckTickCounter++;
                if (battleStuckTickCounter >= BATTLE_TIMEOUT_TICKS) {
                    LOGGER.warn("[AutoCobble] Battle timeout reached. Force exiting.");
                    mc.setScreen(null);

                    KeyMapping recallKey = null;
                    for (KeyMapping key : mc.options.keyMappings) {
                        String name = key.getName();
                        if (name.contains("recall") || "key.cobblemon.recall".equals(name)
                                || "cobblemon.key.recall".equals(name)) {
                            recallKey = key;
                            break;
                        }
                    }
                    if (recallKey != null) {
                        recallKey.setDown(true);
                        pressedKey = recallKey;
                        releaseKeyTick = 2;
                    }

                    inBattle = false;
                    targetPokemon = null;
                    needHealing = false;

                    if (baritonePathing && player.connection != null) {
                        player.connection.sendChat("#stop");
                        baritonePathing = false;
                    }
                    return;
                }

                if (actionCooldown == 0 && enabled) {
                    int prevCooldown = actionCooldown;
                    handleBattleGUI((BattleGUI) mc.screen, mc, player);
                    if (actionCooldown > prevCooldown) {
                        battleStuckTickCounter = 0;
                    }
                }
            }
        }

        // ──────────────────────────────────
        // バトル終了検知と回復チェック
        // ──────────────────────────────────
        if (inBattle && (mc.screen == null || !(mc.screen instanceof BattleGUI))) {
            inBattle = false;
            battleStuckTickCounter = 0;

            boolean shouldHeal = false;
            try {
                PlayerPartyStore party = Cobblemon.INSTANCE.getStorage().getParty(player.getUUID());
                if (party != null) {
                    for (int i = 0; i < party.size(); i++) {
                        Pokemon p = party.get(i);
                        if (p == null)
                            continue;

                        float hpPct = (float) p.getCurrentHealth() / p.getHp();

                        if (i == 0) {
                            // 先頭ポケモン: HP 50%以下、または瀕死
                            if (hpPct <= 0.5f || p.getCurrentHealth() <= 0) {
                                shouldHeal = true;
                                player.displayClientMessage(
                                        java.util.Objects.requireNonNull(Component.literal("§e[AutoCobble] 先頭の "
                                                + p.getDisplayName().getString() + " のHPが低下しています ("
                                                + (int) (hpPct * 100)
                                                + "%)")),
                                        false);
                                break;
                            }

                            // 先頭ポケモン: PP枯渇チェック
                            MoveSet moveset = p.getMoveSet();
                            boolean lowPp = false;
                            for (Move move : moveset.getMoves()) {
                                if (move != null && move.getCurrentPp() <= 3 && move.getCurrentPp() < move.getMaxPp()) {
                                    lowPp = true;
                                    player.displayClientMessage(
                                            java.util.Objects.requireNonNull(Component.literal("§e[AutoCobble] 先頭の "
                                                    + p.getDisplayName().getString() + " のPPが僅かです ("
                                                    + move.getCurrentPp()
                                                    + ")")),
                                            false);
                                    break;
                                }
                            }
                            if (lowPp) {
                                shouldHeal = true;
                                break;
                            }

                            // 先頭ポケモン: 状態異常チェック
                            PersistentStatusContainer status = p.getStatus();
                            if (status != null && status.getStatus() != null) {
                                String statusId = status.getStatus().getName().getPath().toLowerCase();
                                if (statusId.contains("poison") || statusId.contains("paralysis")
                                        || statusId.contains("sleep") || statusId.contains("burn")
                                        || statusId.contains("freeze")) {
                                    shouldHeal = true;
                                    player.displayClientMessage(
                                            java.util.Objects.requireNonNull(Component.literal("§e[AutoCobble] 先頭の "
                                                    + p.getDisplayName().getString() + " が状態異常です (" + statusId + ")")),
                                            false);
                                    break;
                                }
                            }
                        } else {
                            // 2番目以降のポケモン: 瀕死の場合のみ回復
                            if (p.getCurrentHealth() <= 0) {
                                shouldHeal = true;
                                player.displayClientMessage(
                                        java.util.Objects.requireNonNull(Component.literal("§e[AutoCobble] 控えの "
                                                + p.getDisplayName().getString() + " が瀕死です")),
                                        false);
                                break;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("[AutoCobble] Error checking party status for healing: ", e);
                shouldHeal = true;
            }

            needHealing = shouldHeal;
            lastBattleEndTime = mc.level != null ? mc.level.getGameTime() : 0;
            // 戦闘終了直後に最速で次のターゲットを探すようにカウンターをリセット
            scanTickCounter = SCAN_INTERVAL;

            if (needHealing) {
                // 回復理由を通知（1匹分だけ代表して表示）
                LOGGER.info("[AutoCobble] Exited Battle Screen. Party needs healing. Set needHealing = true");
            } else {
                LOGGER.info("[AutoCobble] Exited Battle Screen. Party is healthy.");

                // 回復が不要な場合、もし夜で徹夜日数を満たしていればすぐにベッドに向かうかチェック
                if (mc.level != null && nightsPassedWithoutSleep >= 3) {
                    long timeOfDay = mc.level.getDayTime() % 24000;
                    if (timeOfDay >= 12500 && timeOfDay <= 23000) {
                        needSleep = true;
                        LOGGER.info(
                                "[AutoCobble] Post-battle check: 3 nights passed and it is night. needSleep set to true.");
                    }
                }
            }
        }

        if (mc.screen != null) {
            return;
        }

        // ──────────────────────────────────
        // 1) トグルキーの押下チェック
        // ──────────────────────────────────
        while (KeyBindings.TOGGLE_KEY.consumeClick()) {
            enabled = !enabled;
            scanTickCounter = 0;
            chatTickCounter = 0;

            if (!enabled) {
                mc.options.keyUp.setDown(false);
                mc.options.keySprint.setDown(false);
                if (baritonePathing && player.connection != null) {
                    player.connection.sendChat("#stop");
                    baritonePathing = false;
                }
            }

            String statusValue = enabled ? "§a有効" : "§c無効";
            player.displayClientMessage(Objects.requireNonNull(Component.literal("[AutoCobble] " + statusValue)),
                    false);
            LOGGER.info("[AutoCobble] toggled: {}", enabled ? "ON" : "OFF");
        }

        // ──────────────────────────────────
        // 2) 自動化ON時: 状態に応じた処理 (逃走 or 回復 or ポケモン検知＆移動)
        // ──────────────────────────────────
        if (!enabled) {
            return;
        }

        if (needHealing) {
            handleHealingMovement(mc, player);
            return;
        }

        if (needSleep || isWaitingForMorning) {
            handleSleepMovement(mc, player);
            return;
        }

        // 定期的にポケモンをスキャンしてターゲットを更新
        scanTickCounter++;
        if (scanTickCounter >= SCAN_INTERVAL) {
            scanTickCounter = 0;
            updateTarget(player);
        }

        // ターゲットが無効になった場合はクリア
        PokemonEntity currentTarget = targetPokemon;
        if (currentTarget != null && (!currentTarget.isAlive() || currentTarget.isRemoved())) {
            targetPokemon = null;
            currentTarget = null;
        }

        // ターゲットがいる場合、毎Tickポケモンに向かって移動
        if (currentTarget != null) {
            moveTowardTarget(mc, player, currentTarget);
        } else {
            // 戦闘終了直後（100ティック = 5秒間）は待機移動を開始しない
            long currentTime = mc.level != null ? mc.level.getGameTime() : 0;
            if (currentTime - lastBattleEndTime > 100) {
                handleIdleMovement(mc, player);
            }
        }
    }

    /**
     * 待機状態の処理。ポケモンがいない場合、その場で停止して待機する。
     */
    private static void handleIdleMovement(Minecraft mc, LocalPlayer player) {
        if (baritonePathing && player.connection != null) {
            player.connection.sendChat("#stop");
            baritonePathing = false;
        }

        mc.options.keyUp.setDown(false);
        mc.options.keySprint.setDown(false);
    }

    private static void handleHealingMovement(Minecraft mc, LocalPlayer player) {
        healingScanTickCounter++;
        if (healingScanTickCounter >= SCAN_INTERVAL) {
            healingScanTickCounter = 0;

            if (targetHealingMachine == null) {
                targetHealingMachine = findHealingMachine(player);
                if (targetHealingMachine == null) {
                    player.displayClientMessage(
                            java.util.Objects.requireNonNull(net.minecraft.network.chat.Component.literal(
                                    "§c[AutoCobble] Healing Machine not found! Auto system disabled for safety.")),
                            false);
                    LOGGER.warn("[AutoCobble] Healing Machine not found! Disabling auto.");
                    enabled = false;
                    needHealing = false;
                    mc.options.keyUp.setDown(false);
                    mc.options.keySprint.setDown(false);
                    if (baritonePathing && player.connection != null) {
                        player.connection.sendChat("#stop");
                        baritonePathing = false;
                    }
                    healingStuckTickCounter = 0;
                    return;
                } else {
                    LOGGER.info("[AutoCobble] Healing Machine found at: " + targetHealingMachine);
                    healingStuckTickCounter = 0; // 新規探索時にリセット
                }
            }
        }

        if (targetHealingMachine != null) {
            double dx = targetHealingMachine.getX() + 0.5 - player.getX();
            double dy = targetHealingMachine.getY() + 0.5 - player.getY();
            double dz = targetHealingMachine.getZ() + 0.5 - player.getZ();
            double distSq = dx * dx + dy * dy + dz * dz;

            if (distSq <= 4.0) { // 距離が2ブロック以下
                if (baritonePathing && player.connection != null) {
                    player.connection.sendChat("#stop");
                    baritonePathing = false;
                }
                mc.options.keyUp.setDown(false);
                mc.options.keySprint.setDown(false);
                mc.options.keyJump.setDown(false);

                if (actionCooldown == 0) {
                    float targetYaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
                    player.setYRot(targetYaw);
                    player.setXRot((float) -(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * (180.0 / Math.PI)));

                    BlockHitResult hitResult = new BlockHitResult(
                            new Vec3(targetHealingMachine.getX() + 0.5, targetHealingMachine.getY() + 0.5,
                                    targetHealingMachine.getZ() + 0.5),
                            Direction.UP,
                            java.util.Objects.requireNonNull(targetHealingMachine),
                            false);

                    if (mc.gameMode != null) {
                        mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
                        LOGGER.info("[AutoCobble] Interacted with Healing Machine.");
                    }

                    needHealing = false;
                    targetHealingMachine = null;
                    healingStuckTickCounter = 0;
                    actionCooldown = 60; // インタラクト後のクールダウン
                }
            } else {
                // 回復装置に向かっている時間を計測
                healingStuckTickCounter++;
                if (healingStuckTickCounter > 200) { // 10秒経過しても到着できなかった場合
                    LOGGER.warn("[AutoCobble] Healing machine approach timed out (stuck). Retrying pathing...");
                    if (baritonePathing && player.connection != null) {
                        player.connection.sendChat("#stop");
                        baritonePathing = false;
                    }
                    // 目標をリセットして次のTickで再スキャンを促す
                    targetHealingMachine = null;
                    healingStuckTickCounter = 0;
                    mc.options.keyUp.setDown(false);
                    mc.options.keySprint.setDown(false);
                    return;
                }

                if (!baritonePathing && player.connection != null) {
                    player.connection.sendChat("#goto " + targetHealingMachine.getX() + " "
                            + targetHealingMachine.getY() + " " + targetHealingMachine.getZ());
                    baritonePathing = true;
                }
                // 移動中は常に進行方向に視点を向ける
                float movingYaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
                player.setYRot(movingYaw);
            }
        }
    }

    private static void handleSleepMovement(Minecraft mc, LocalPlayer player) {
        if (mc.level == null)
            return;

        // 夜間（12500〜23000）のみ寝ることができる
        long time = mc.level.getDayTime() % 24000;
        if (time < 12500 || time > 23000) {
            if (needSleep) {
                LOGGER.info("[AutoCobble] Not night time yet. Waiting to sleep.");
            }
            // 朝になったらリセット
            if (time < 1000) {
                if (needSleep || isWaitingForMorning) {
                    needSleep = false;
                    isWaitingForMorning = false;
                    nightsPassedWithoutSleep = 0;
                    targetBed = null;
                    LOGGER.info("[AutoCobble] Morning reached. Resetting sleep status and resuming work.");
                }
            }
            return;
        }

        if (isWaitingForMorning) {
            // 夜中にベッドを使用した直後の待機時間。
            // プレイヤーが睡眠状態かを監視し、キャンセル（ベッドから出た／入れなかった）を検知する
            if (!player.isSleeping()) {
                sleepCancelTickCounter++;
                if (sleepCancelTickCounter > 60) { // 約3秒連続で起きていたらキャンセルとみなす
                    isWaitingForMorning = false;
                    sleepCancelTickCounter = 0;
                    targetBed = null;
                    LOGGER.info("[AutoCobble] Player is not sleeping. Sleep canceled. Resuming auto-exploration.");
                }
            } else {
                sleepCancelTickCounter = 0; // 寝ていればリセット
            }

            // 行動を停止して朝を待機（またはキャンセルされるまで）、ここから下には進まない
            return;
        }

        sleepScanTickCounter++;
        if (sleepScanTickCounter >= SCAN_INTERVAL) {
            sleepScanTickCounter = 0;

            if (targetBed == null) {
                targetBed = findBed(player);
                if (targetBed == null) {
                    if (chatTickCounter % 5 == 0) {
                        player.displayClientMessage(
                                java.util.Objects.requireNonNull(
                                        Component.literal("§c[AutoCobble] Bed not found! Cannot sleep.")),
                                false);
                    }
                    return;
                } else {
                    LOGGER.info("[AutoCobble] Bed found at: {}", targetBed);
                }
            }
        }

        if (targetBed != null) {
            double dx = targetBed.getX() + 0.5 - player.getX();
            double dy = targetBed.getY() + 0.5 - player.getY();
            double dz = targetBed.getZ() + 0.5 - player.getZ();
            double distSq = dx * dx + dy * dy + dz * dz;

            if (distSq <= 4.0) { // 2ブロック以内
                if (baritonePathing && player.connection != null) {
                    player.connection.sendChat("#stop");
                    baritonePathing = false;
                }
                mc.options.keyUp.setDown(false);

                if (actionCooldown == 0) {
                    float targetYaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
                    player.setYRot(targetYaw);
                    player.setXRot(0); // 真っ直ぐ見る

                    BlockHitResult hitResult = new BlockHitResult(
                            new Vec3(targetBed.getX() + 0.5, targetBed.getY() + 0.5, targetBed.getZ() + 0.5),
                            Direction.UP,
                            java.util.Objects.requireNonNull(targetBed),
                            false);

                    if (mc.gameMode != null) {
                        mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
                        LOGGER.info("[AutoCobble] Interacted with Bed. Waiting for morning.");
                    }

                    // 寝る動作に入ったら、朝になるまで待機状態に移行する
                    needSleep = false;
                    isWaitingForMorning = true;
                    targetBed = null;
                    actionCooldown = 100; // 長めの待機
                }
            } else {
                if (!baritonePathing && player.connection != null) {
                    player.connection
                            .sendChat("#goto " + targetBed.getX() + " " + targetBed.getY() + " " + targetBed.getZ());
                    baritonePathing = true;
                }
                float movingYaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
                player.setYRot(movingYaw);
            }
        }
    }

    private static BlockPos findBed(LocalPlayer player) {
        BlockPos center = player.blockPosition();
        int rx = 45;
        int ry = 15;
        int rz = 45;

        BlockPos pos1 = center.offset(-rx, -ry, -rz);
        BlockPos pos2 = center.offset(rx, ry, rz);

        return BlockPos
                .betweenClosedStream(java.util.Objects.requireNonNull(pos1), java.util.Objects.requireNonNull(pos2))
                .filter(pos -> {
                    net.minecraft.world.level.block.state.BlockState state = player.level()
                            .getBlockState(java.util.Objects.requireNonNull(pos));
                    return state.getBlock() instanceof net.minecraft.world.level.block.BedBlock;
                })
                .map(BlockPos::immutable)
                .min(java.util.Comparator.comparingDouble(pos -> pos.distSqr(center)))
                .orElse(null);
    }

    private static BlockPos findHealingMachine(LocalPlayer player) {
        BlockPos center = player.blockPosition();
        int rx = 45;
        int ry = 15;
        int rz = 45;

        BlockPos pos1 = center.offset(-rx, -ry, -rz);
        BlockPos pos2 = center.offset(rx, ry, rz);

        return BlockPos
                .betweenClosedStream(java.util.Objects.requireNonNull(pos1), java.util.Objects.requireNonNull(pos2))
                .filter(pos -> {
                    net.minecraft.world.level.block.state.BlockState state = player.level()
                            .getBlockState(java.util.Objects.requireNonNull(pos));
                    net.minecraft.resources.ResourceLocation registryName = ForgeRegistries.BLOCKS
                            .getKey(state.getBlock());
                    return registryName != null && registryName.getNamespace().equals("cobblemon")
                            && registryName.getPath().equals("healing_machine");
                })
                .map(BlockPos::immutable)
                .min(java.util.Comparator.comparingDouble(pos -> pos.distSqr(center)))
                .orElse(null);
    }

    /**
     * バトル画面での自動アクションロジック
     */
    private static void handleBattleGUI(BattleGUI gui, Minecraft mc, LocalPlayer player) {
        ClientBattleActor actor = gui.getActor();
        if (actor == null) {
            LOGGER.info("[AutoCobble] handleBattleGUI: actor is null");
            return;
        }

        BattleActionSelection selection = gui.getCurrentActionSelection();
        if (selection == null) {
            LOGGER.info("[AutoCobble] handleBattleGUI: selection is null");
            return;
        }

        SingleActionRequest request = selection.getRequest();
        if (request == null) {
            LOGGER.info("[AutoCobble] handleBattleGUI: request is null");
            return;
        }

        ActiveClientBattlePokemon myActive = request.getActivePokemon();
        if (myActive == null) {
            LOGGER.info("[AutoCobble] handleBattleGUI: myActive is null");
            return;
        }

        ActiveClientBattlePokemon opponent = null;
        for (ActiveClientBattlePokemon p : myActive.getAllActivePokemon()) {
            if (!myActive.isAllied(p)) {
                opponent = p;
                break;
            }
        }

        if (opponent == null) {
            LOGGER.info("[AutoCobble] handleBattleGUI: opponent is null");
            return;
        }

        ClientBattlePokemon myPokemon = myActive.getBattlePokemon();
        ClientBattlePokemon oppPokemon = opponent.getBattlePokemon();

        float myHpPct = myPokemon.getHpValue() / myPokemon.getMaxHp();
        float oppHpPct = oppPokemon.getHpValue() / oppPokemon.getMaxHp();

        Set<String> aspects = oppPokemon.getAspects();
        boolean isCatchTarget = false;
        if (aspects != null) {
            String lower = aspects.toString().toLowerCase();
            if (lower.contains("shiny") || lower.contains("legendary") || lower.contains("mythical")
                    || lower.contains("ultrabeast")) {
                isCatchTarget = true;
            }
        }
        // aspectsで検出できない場合のフォールバック: 種族名から伝説判定
        if (!isCatchTarget) {
            String species = oppPokemon.getSpecies().getName().toLowerCase();
            if (isLegendaryByName(species)) {
                isCatchTarget = true;
            }
        }

        // --- 2. 交代ロジック ---
        boolean forceSwitch = request.getForceSwitch();
        if (forceSwitch || myHpPct <= 0.2f) {
            List<Pokemon> party = actor.getPokemon();
            for (Pokemon p : party) {
                if (!p.getUuid().equals(myPokemon.getUuid())) {
                    if (p.getHp() > 0 && ((float) p.getCurrentHealth() / p.getHp()) > 0.2f) {
                        gui.selectAction(request, new SwitchActionResponse(p.getUuid()));
                        actionCooldown = 40;
                        LOGGER.info("[AutoCobble] Switched to Pokemon with UUID: " + p.getUuid());
                        return;
                    }
                }
            }
            // 控えに20%以上のポケモンがいない場合、強制交代なら生きてるポケモンを出す
            if (forceSwitch) {
                for (Pokemon p : party) {
                    if (!p.getUuid().equals(myPokemon.getUuid()) && p.getCurrentHealth() > 0) {
                        gui.selectAction(request, new SwitchActionResponse(p.getUuid()));
                        actionCooldown = 40;
                        LOGGER.info("[AutoCobble] Forced switch to Pokemon with UUID: " + p.getUuid());
                        return;
                    }
                }
            }
            // 強制交代でないなら徹底抗戦として下へ続く
        }

        if (forceSwitch) {
            LOGGER.info("[AutoCobble] handleBattleGUI: forceSwitch is true, but no pokemon available to switch to");
            return; // 交代必須だが交代先がいない（全滅）などの場合はこれ以上行動不能
        }

        // --- 1. & 3. 攻撃・捕獲ロジック ---
        ShowdownMoveset moveSet = request.getMoveSet();
        if (moveSet == null || moveSet.getMoves() == null || moveSet.getMoves().isEmpty()) {
            LOGGER.info("[AutoCobble] handleBattleGUI: moveSet is null or empty");
            return;
        }

        if (isCatchTarget) {
            // HP 15%以下ならボールを投げる（従来の30%→15%に厳格化して削りすぎを防止）
            // HP 10%以下なら絶対に攻撃しない（倒す危険があるため）
            if (oppHpPct <= 0.5f) {
                // ボール投擲 (ターゲットとして targetPokemon を渡す)
                throwPokeBallFromHotbar(mc, player, targetPokemon);
                return;
            } else if (oppHpPct <= 0.1f) {
                // 安全ガード: HPが10%以下なら何もしない（攻撃禁止）
                LOGGER.warn("[AutoCobble] isCatchTarget HP is critically low ({})! Skipping action to avoid KO.",
                        oppHpPct);
                actionCooldown = 20;
                return;
            } else {
                // 安全な削り
                InBattleMove bestMove = null;
                boolean foundFalseSwipe = false;

                // 2ターン技などで「使わなければならない」技が優先
                for (InBattleMove move : moveSet.getMoves()) {
                    if (move.mustBeUsed()) {
                        bestMove = move;
                        break;
                    }
                }

                if (bestMove == null) {
                    for (InBattleMove move : moveSet.getMoves()) {
                        if (!move.canBeUsed())
                            continue;

                        if (move.getId().equalsIgnoreCase("falseswipe")) {
                            bestMove = move;
                            foundFalseSwipe = true;
                            break;
                        }
                    }
                }

                if (bestMove == null && !foundFalseSwipe) {
                    // スコア（威力）を正確に計算する。STATUS技は選択しない。
                    // 削りのため、威力が0より大きく最も低い技を選ぶ
                    double minPower = 9999.0;
                    for (InBattleMove move : moveSet.getMoves()) {
                        if (!move.canBeUsed() || move.getPp() <= 0)
                            continue;

                        MoveTemplate tmpl = Moves.INSTANCE.getByName(move.getId());
                        if (tmpl != null) {
                            if (tmpl.getDamageCategory() == DamageCategories.INSTANCE.getSTATUS()) {
                                continue;
                            }
                            double power = tmpl.getPower();
                            double multiplier = calculateEffectiveness(move, oppPokemon);
                            double score = power * multiplier;

                            // 削りのため、効果がない（0.0）技は選べない。
                            // また、威力が最も低いものを選ぶ。
                            if (score > 0 && power < minPower) {
                                minPower = power;
                                bestMove = move;
                            }
                        }
                    }

                    if (bestMove == null) {
                        // 攻撃技がない場合のフォールバック（変化技は除く）
                        for (InBattleMove move : moveSet.getMoves()) {
                            if (!move.canBeUsed() || move.getPp() <= 0)
                                continue;
                            MoveTemplate tmpl = Moves.INSTANCE.getByName(move.getId());
                            if (tmpl != null && tmpl.getDamageCategory() == DamageCategories.INSTANCE.getSTATUS())
                                continue;
                            bestMove = move;
                            break;
                        }
                        if (bestMove == null) {
                            bestMove = moveSet.getMoves().get(0);
                        }
                    }
                }

                if (bestMove != null) {
                    gui.selectAction(request, createMoveAction(bestMove, opponent));
                    actionCooldown = 40;
                    LOGGER.info("[AutoCobble] Safely attacked using Move: " + bestMove.getId());
                } else {
                    LOGGER.info("[AutoCobble] handleBattleGUI: No valid moves available for catch target");
                }
                return;
            }
        }

        // 捕獲対象でない場合の通常攻撃
        InBattleMove bestAttack = null;
        double maxPower = -1.0;

        // 2ターン技などで「使わなければならない」技が優先
        for (InBattleMove move : moveSet.getMoves()) {
            if (move.mustBeUsed()) {
                bestAttack = move;
                break;
            }
        }

        if (bestAttack == null) {
            for (InBattleMove move : moveSet.getMoves()) {
                if (!move.canBeUsed() || move.getPp() <= 0)
                    continue;

                MoveTemplate tmpl = Moves.INSTANCE.getByName(move.getId());
                if (tmpl != null) {
                    if (tmpl.getDamageCategory() == DamageCategories.INSTANCE.getSTATUS()) {
                        continue;
                    }
                    double power = tmpl.getPower();
                    double multiplier = calculateEffectiveness(move, oppPokemon);
                    double score = power * multiplier;

                    if (score > maxPower) {
                        maxPower = score;
                        bestAttack = move;
                    }
                }
            }
        }

        if (bestAttack == null) {
            // 攻撃技がない場合のフォールバック（変化技は除く）
            for (InBattleMove move : moveSet.getMoves()) {
                if (!move.canBeUsed() || move.getPp() <= 0)
                    continue;
                MoveTemplate tmpl = Moves.INSTANCE.getByName(move.getId());
                if (tmpl != null && tmpl.getDamageCategory() == DamageCategories.INSTANCE.getSTATUS())
                    continue;
                bestAttack = move;
                break;
            }
            if (bestAttack == null) {
                bestAttack = moveSet.getMoves().get(0);
            }
        }

        if (bestAttack != null) {
            gui.selectAction(request, createMoveAction(bestAttack, opponent));
            actionCooldown = 40;
            LOGGER.info("[AutoCobble] Attacked using Move: " + bestAttack.getId());
        } else {
            LOGGER.info("[AutoCobble] handleBattleGUI: No valid moves available for normal target");
        }
    }

    private static void throwPokeBallFromHotbar(Minecraft mc, LocalPlayer player, Entity target) {
        // ホットバーからボールを探す
        int ballSlot = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            String itemName = stack.getItem().toString().toLowerCase();
            if (itemName.contains("poke_ball") || itemName.contains("ball")) {
                ballSlot = i;
                break;
            }
        }

        if (ballSlot != -1) {
            // 投げる前にターゲットに視線を合わせる (再エイム)
            if (target != null) {
                Vec3 eyePos = player.getEyePosition();
                double targetX = target.getX();
                double targetY = target.getY() + target.getBbHeight() / 2.0;
                double targetZ = target.getZ();

                double dxAim = targetX - eyePos.x;
                double dyAim = targetY - eyePos.y;
                double dzAim = targetZ - eyePos.z;
                double horizontalAimDist = Math.sqrt(dxAim * dxAim + dzAim * dzAim);

                float targetYaw = (float) (Math.atan2(dzAim, dxAim) * (180.0 / Math.PI)) - 90.0F;
                float targetPitch = (float) -(Math.atan2(dyAim, horizontalAimDist) * (180.0 / Math.PI));

                player.setYRot(targetYaw);
                player.setXRot(targetPitch);
                LOGGER.info("[AutoCobble] Rotated to target before throwing PokeBall");
            }

            player.getInventory().selected = ballSlot;
            // 右クリックパケットの送信等により投擲
            if (mc.gameMode != null) {
                mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
            }
            actionCooldown = 40;
            LOGGER.info("[AutoCobble] Threw a PokeBall from Hotbar slot " + ballSlot);
        } else {
            LOGGER.warn("[AutoCobble] Target requires capturing, but no Poke Balls found in hotbar!");
            actionCooldown = 120; // ログスパム防止のため長めに待機
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
            if (targetPokemon != null) {
                targetPokemon = null;
                lastTargetX = 0;
                lastTargetY = 0;
                lastTargetZ = 0;
            }
            return;
        }

        // 最も近いポケモンをターゲットに設定 (所有されているポケモンは除外、さらに10ブロック以上下のポケモンは落下死防止のため除外)
        PokemonEntity closest = nearbyPokemon.stream()
                .filter(e -> e.getOwnerUUID() == null && (player.getY() - e.getY() < 10.0))
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(player)))
                .orElse(null);

        if (closest == null)
            return;

        boolean isNewTarget = targetPokemon == null || !targetPokemon.getUUID().equals(closest.getUUID());

        // 新しい対象か、または対象が前回の目標地点から4ブロック以上移動した場合のみ目標更新
        double dx = closest.getX() - lastTargetX;
        double dy = closest.getY() - lastTargetY;
        double dz = closest.getZ() - lastTargetZ;
        double distSqFromLastGoal = dx * dx + dy * dy + dz * dz;

        if (isNewTarget || distSqFromLastGoal > 16.0) {
            targetPokemon = closest;
            if (isNewTarget) {
                LOGGER.info("[AutoCobble] Navigating to new target: " + closest.getDisplayName().getString());
            } else {
                LOGGER.info("[AutoCobble] Target moved significantly. Updating goal.");
            }

            lastTargetX = targetPokemon.getX();
            lastTargetY = targetPokemon.getY();
            lastTargetZ = targetPokemon.getZ();

            // 目標が変わったらパス再計算
            baritonePathing = false;
        }

        // チャット通知（スパム防止）
        chatTickCounter++;
        if (chatTickCounter >= CHAT_INTERVAL / SCAN_INTERVAL) {
            chatTickCounter = 0;
            String name = closest.getDisplayName().getString();
            int x = (int) closest.getX();
            int y = (int) closest.getY();
            int z = (int) closest.getZ();
            // GUI表示用なのでここはMath.sqrtを許容
            double dist = Math.sqrt(closest.distanceToSqr(player));

            player.displayClientMessage(
                    Objects.requireNonNull(Component.literal(
                            Objects.requireNonNull(String.format("§eTarget: §b%s §e(%.1fブロック先) X:%d Y:%d Z:%d",
                                    name, dist, x, y, z)))),
                    true); // trueでアクションバーに表示（チャット欄を汚さない）
        }
    }

    /**
     * ターゲットポケモンに向かってプレイヤーを移動させる。
     * プレイヤーの視線をポケモンに向け、前進キーを押し続ける。
     */
    private static void moveTowardTarget(Minecraft mc, LocalPlayer player, @Nonnull Entity target) {
        double distSq = player.distanceToSqr(target);

        // 3.0ブロック以下の場合は到達と判定し、エイム＆インタラクトを実行
        if (distSq <= 9.0) { // 3.0 * 3.0
            if (baritonePathing && player.connection != null) {
                player.connection.sendChat("#stop");
                baritonePathing = false;
            }
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
                    String name = key.getName();
                    String category = key.getCategory();

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
                        String name = key.getName();
                        if ("key.cobblemon.send_out".equals(name)
                                || "cobblemon.key.send_out".equals(name)) {
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

        if (!baritonePathing && player.connection != null) {
            player.connection
                    .sendChat("#goto " + (int) target.getX() + " " + (int) target.getY() + " " + (int) target.getZ());
            baritonePathing = true;
        }

        // 2.0ブロック以上の場合はターゲットに向かって移動（視点制御）
        double dxMove = target.getX() - player.getX();
        double dzMove = target.getZ() - player.getZ();
        float movingYaw = (float) (Math.atan2(dzMove, dxMove) * (180.0 / Math.PI)) - 90.0F;
        player.setYRot(movingYaw);
    }

    private static double calculateEffectiveness(InBattleMove move, ClientBattlePokemon opponent) {
        MoveTemplate tmpl = Moves.INSTANCE.getByName(move.getId());
        if (tmpl == null)
            return 1.0;

        String attackType = tmpl.getElementalType().getName().toLowerCase();

        double multiplier = 1.0;
        Iterable<ElementalType> types = opponent.getSpecies().getTypes();
        for (ElementalType type : types) {
            multiplier *= getTypeMultiplier(attackType, type.getName().toLowerCase());
        }

        String ability = opponent.getProperties().getAbility();
        String species = opponent.getSpecies().getName().toLowerCase();
        if (ability != null) {
            ability = ability.toLowerCase();
        } else {
            // Safe fallback for user's specific case
            if (species.equals("deerling") || species.equals("sawsbuck") || species.equals("marill")
                    || species.equals("azumarill") || species.equals("azurill") || species.equals("bouffalant")
                    || species.equals("miltank") || species.equals("girafarig") || species.equals("farigiraf")
                    || species.equals("stantler") || species.equals("wyrdeer") || species.equals("zebstrika")
                    || species.equals("blitzle") || species.equals("gogoat") || species.equals("skiddo")
                    || species.equals("drampa"))
                ability = "sapsipper";
            if (species.equals("lapras") || species.equals("vaporeon") || species.equals("quagsire")
                    || species.equals("clodsire") || species.equals("mantine") || species.equals("politoed"))
                ability = "waterabsorb";
            if (species.equals("jolteon") || species.equals("lanturn") || species.equals("thundurus"))
                ability = "voltabsorb";
            if (species.equals("growlithe") || species.equals("arcanine") || species.equals("ponyta")
                    || species.equals("rapidash") || species.equals("flareon") || species.equals("houndour")
                    || species.equals("houndoom") || species.equals("heatran") || species.equals("litwick")
                    || species.equals("lampent") || species.equals("chandelure"))
                ability = "flashfire";
        }

        if (ability != null) {
            switch (ability) {
                case "sapsipper":
                    if (attackType.equals("grass"))
                        return 0.0;
                    break;
                case "waterabsorb":
                case "dryskin":
                case "stormdrain":
                    if (attackType.equals("water"))
                        return 0.0;
                    break;
                case "voltabsorb":
                case "motordrive":
                case "lightningrod":
                    if (attackType.equals("electric"))
                        return 0.0;
                    break;
                case "flashfire":
                case "wellbakedbody":
                    if (attackType.equals("fire"))
                        return 0.0;
                    break;
                case "levitate":
                case "eartheater":
                    if (attackType.equals("ground"))
                        return 0.0;
                    break;
                case "thickfat":
                    if (attackType.equals("fire") || attackType.equals("ice"))
                        return 0.5;
                    break;
                case "purifyingsalt":
                    if (attackType.equals("ghost"))
                        return 0.5;
                    break;
                case "waterbubble":
                    if (attackType.equals("fire"))
                        return 0.5;
                    break;
                case "fluffy":
                    if (attackType.equals("fire"))
                        return 2.0;
                    break;
            }
        }

        if ("wonderguard".equals(ability)) {
            if (multiplier <= 1.0)
                return 0.0;
        }

        return multiplier;
    }

    private static double getTypeMultiplier(String attackType, String defendType) {
        switch (attackType) {
            case "normal":
                switch (defendType) {
                    case "rock":
                    case "steel":
                        return 0.5;
                    case "ghost":
                        return 0.0;
                    default:
                        return 1.0;
                }
            case "fire":
                switch (defendType) {
                    case "fire":
                    case "water":
                    case "rock":
                    case "dragon":
                        return 0.5;
                    case "grass":
                    case "ice":
                    case "bug":
                    case "steel":
                        return 2.0;
                    default:
                        return 1.0;
                }
            case "water":
                switch (defendType) {
                    case "water":
                    case "grass":
                    case "dragon":
                        return 0.5;
                    case "fire":
                    case "ground":
                    case "rock":
                        return 2.0;
                    default:
                        return 1.0;
                }
            case "electric":
                switch (defendType) {
                    case "electric":
                    case "grass":
                    case "dragon":
                        return 0.5;
                    case "water":
                    case "flying":
                        return 2.0;
                    case "ground":
                        return 0.0;
                    default:
                        return 1.0;
                }
            case "grass":
                switch (defendType) {
                    case "fire":
                    case "grass":
                    case "poison":
                    case "flying":
                    case "bug":
                    case "dragon":
                    case "steel":
                        return 0.5;
                    case "water":
                    case "ground":
                    case "rock":
                        return 2.0;
                    default:
                        return 1.0;
                }
            case "ice":
                switch (defendType) {
                    case "fire":
                    case "water":
                    case "ice":
                    case "steel":
                        return 0.5;
                    case "grass":
                    case "ground":
                    case "flying":
                    case "dragon":
                        return 2.0;
                    default:
                        return 1.0;
                }
            case "fighting":
                switch (defendType) {
                    case "poison":
                    case "flying":
                    case "psychic":
                    case "bug":
                    case "fairy":
                        return 0.5;
                    case "normal":
                    case "ice":
                    case "rock":
                    case "dark":
                    case "steel":
                        return 2.0;
                    case "ghost":
                        return 0.0;
                    default:
                        return 1.0;
                }
            case "poison":
                switch (defendType) {
                    case "poison":
                    case "ground":
                    case "rock":
                    case "ghost":
                        return 0.5;
                    case "grass":
                    case "fairy":
                        return 2.0;
                    case "steel":
                        return 0.0;
                    default:
                        return 1.0;
                }
            case "ground":
                switch (defendType) {
                    case "grass":
                    case "bug":
                        return 0.5;
                    case "fire":
                    case "electric":
                    case "poison":
                    case "rock":
                    case "steel":
                        return 2.0;
                    case "flying":
                        return 0.0;
                    default:
                        return 1.0;
                }
            case "flying":
                switch (defendType) {
                    case "electric":
                    case "rock":
                    case "steel":
                        return 0.5;
                    case "grass":
                    case "fighting":
                    case "bug":
                        return 2.0;
                    default:
                        return 1.0;
                }
            case "psychic":
                switch (defendType) {
                    case "psychic":
                    case "steel":
                        return 0.5;
                    case "fighting":
                    case "poison":
                        return 2.0;
                    case "dark":
                        return 0.0;
                    default:
                        return 1.0;
                }
            case "bug":
                switch (defendType) {
                    case "fire":
                    case "fighting":
                    case "poison":
                    case "flying":
                    case "ghost":
                    case "steel":
                    case "fairy":
                        return 0.5;
                    case "grass":
                    case "psychic":
                    case "dark":
                        return 2.0;
                    default:
                        return 1.0;
                }
            case "rock":
                switch (defendType) {
                    case "fighting":
                    case "ground":
                    case "steel":
                        return 0.5;
                    case "fire":
                    case "ice":
                    case "flying":
                    case "bug":
                        return 2.0;
                    default:
                        return 1.0;
                }
            case "ghost":
                switch (defendType) {
                    case "dark":
                        return 0.5;
                    case "psychic":
                    case "ghost":
                        return 2.0;
                    case "normal":
                        return 0.0;
                    default:
                        return 1.0;
                }
            case "dragon":
                switch (defendType) {
                    case "steel":
                        return 0.5;
                    case "dragon":
                        return 2.0;
                    case "fairy":
                        return 0.0;
                    default:
                        return 1.0;
                }
            case "dark":
                switch (defendType) {
                    case "fighting":
                    case "dark":
                    case "fairy":
                        return 0.5;
                    case "psychic":
                    case "ghost":
                        return 2.0;
                    default:
                        return 1.0;
                }
            case "steel":
                switch (defendType) {
                    case "fire":
                    case "water":
                    case "electric":
                    case "steel":
                        return 0.5;
                    case "ice":
                    case "rock":
                    case "fairy":
                        return 2.0;
                    default:
                        return 1.0;
                }
            case "fairy":
                switch (defendType) {
                    case "fire":
                    case "poison":
                    case "steel":
                        return 0.5;
                    case "fighting":
                    case "dragon":
                    case "dark":
                        return 2.0;
                    default:
                        return 1.0;
                }
            default:
                return 1.0;
        }
    }

    private static MoveActionResponse createMoveAction(InBattleMove move,
            com.cobblemon.mod.common.client.battle.ActiveClientBattlePokemon opponent) {
        String targetPNX = opponent.getPNX();
        MoveTemplate tmpl = Moves.INSTANCE.getByName(move.getId());
        if (tmpl != null) {
            String targetType = tmpl.getTarget().name();
            // 範囲攻撃（Razor Leafなど）や自身対象の技は特定のターゲットを指定するとエラーになるため、nullを渡す
            if (!targetType.equals("normal") && !targetType.equals("adjacentAlly") && !targetType.equals("adjacentFoe")
                    && !targetType.equals("any")) {
                targetPNX = null;
            }
        }
        return new MoveActionResponse(move.getId(), targetPNX, "");
    }

    private static boolean isLegendaryByName(String species) {
        return LEGENDARY_POKEMON.contains(species.toLowerCase());
    }
}
