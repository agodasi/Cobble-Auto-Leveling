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

    /** ターゲットの座標（目標を更新するかどうかの判定用） */
    private static double lastTargetX = 0;
    private static double lastTargetY = 0;
    private static double lastTargetZ = 0;

    /** バトル中かどうか */
    private static boolean inBattle = false;

    /**
     * バトル終了時に回復ステートへ移行するためのフラグ
     */
    public static boolean needHealing = false;

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
        // GUIが開いている場合は移動を停止してスキップ
        // ──────────────────────────────────
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
                    LOGGER.info("[AutoCobble] Entered Battle Screen.");
                }

                if (actionCooldown == 0 && enabled) {
                    handleBattleGUI((BattleGUI) mc.screen, mc, player);
                }
            } else {
                if (inBattle) {
                    inBattle = false;
                    needHealing = true; // バトル終了後にHEALINGフラグを立てる
                    LOGGER.info("[AutoCobble] Exited Battle Screen. Set needHealing = true");
                }
            }
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
            if (oppHpPct <= 0.3f) {
                // ボール投擲
                throwPokeBallFromHotbar(mc, player);
                return;
            } else {
                // 安全な削り
                InBattleMove bestMove = null;
                boolean foundFalseSwipe = false;

                for (InBattleMove move : moveSet.getMoves()) {
                    if (!move.canBeUsed())
                        continue;

                    if (move.getId().equalsIgnoreCase("falseswipe")) {
                        bestMove = move;
                        foundFalseSwipe = true;
                        break;
                    }
                }

                if (!foundFalseSwipe) {
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

    private static void throwPokeBallFromHotbar(Minecraft mc, LocalPlayer player) {
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

        // 最も近いポケモンをターゲットに設定
        PokemonEntity closest = nearbyPokemon.stream()
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
        }

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

    /**
     * ターゲットポケモンに向かってプレイヤーを移動させる。
     * プレイヤーの視線をポケモンに向け、前進キーを押し続ける。
     */
    private static void moveTowardTarget(Minecraft mc, LocalPlayer player, @Nonnull Entity target) {
        double dist = player.distanceTo(target);

        // 2.0ブロック以下の場合は到達と判定し、エイム＆インタラクトを実行
        if (dist <= 2.0) {
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

        // 2.0ブロック以上の場合はターゲットに向かって移動
        // プレイヤーの視線をポケモンに向ける
        double dx = target.getX() - player.getX();
        double dz = target.getZ() - player.getZ();
        float targetYaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        player.setYRot(targetYaw);

        // 前進キーをONにする
        mc.options.keyUp.setDown(true);

        // スプリント（オプション）
        if (dist > 5.0) {
            mc.options.keySprint.setDown(true);
        } else {
            mc.options.keySprint.setDown(false);
        }

        // 段差がある場合にジャンプを試みる（簡易的な段差越え）
        if (player.horizontalCollision && player.onGround()) {
            mc.options.keyJump.setDown(true);
        } else {
            mc.options.keyJump.setDown(false);
        }
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
}
