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
     * デフォルト: Pキー
     * 設定画面の「AutoCobble」カテゴリに表示される。
     */
    @Nonnull
    public static final KeyMapping TOGGLE_KEY = new KeyMapping(
            "key.autocobble.toggle",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_P,
            "key.categories.autocobble");
}
