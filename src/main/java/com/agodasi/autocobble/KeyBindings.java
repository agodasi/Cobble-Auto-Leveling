package com.agodasi.autocobble;

import javax.annotation.Nonnull;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraftforge.client.settings.KeyConflictContext;

/**
 * キーバインド定義クラス。
 * MODで使用するキーバインドを一元管理する。
 */
public class KeyBindings {

    /**
     * 自動化トグルキー。
     * デフォルト: Rキー
     * 設定画面の「AutoCobble」カテゴリに表示される。
     */
    @Nonnull
    public static final KeyMapping TOGGLE_KEY = new KeyMapping(
            "key.autocobble.toggle", // 翻訳キー
            KeyConflictContext.IN_GAME, // ゲーム内でのみ有効
            InputConstants.Type.KEYSYM, // キーボードキー
            GLFW.GLFW_KEY_R, // デフォルトキー: R
            "key.categories.autocobble" // カテゴリ名
    );
}
