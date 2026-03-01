package com.agodasi.autocobble;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalBlock;

/**
 * Baritone APIのラッパークラス。
 * このクラスを別に分離することで、Baritoneが存在しない環境でも
 * ClientEventsクラスのロードが失敗しないようにする。
 */
public class BaritoneHelper {

    /**
     * Baritone APIが利用可能かどうかを返す。
     */
    public static boolean isAvailable() {
        try {
            Class.forName("baritone.api.BaritoneAPI");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * 指定座標に向かってBaritoneで移動する。
     */
    public static void navigateTo(int x, int y, int z) {
        BaritoneAPI.getProvider().getPrimaryBaritone()
                .getCustomGoalProcess()
                .setGoalAndPath(new GoalBlock(x, y, z));
    }

    /**
     * Baritoneの移動をキャンセルする。
     */
    public static void cancelMovement() {
        BaritoneAPI.getProvider().getPrimaryBaritone()
                .getPathingBehavior()
                .cancelEverything();
    }
}
