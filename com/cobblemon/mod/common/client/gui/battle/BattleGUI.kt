/*
 * Copyright (C) 2023 Cobblemon Contributors
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.cobblemon.mod.common.client.gui.battle

import com.cobblemon.mod.common.battles.ShowdownActionResponse
import com.cobblemon.mod.common.client.CobblemonClient
import com.cobblemon.mod.common.client.battle.ClientBattleActor
import com.cobblemon.mod.common.client.battle.SingleActionRequest
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleActionSelection
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleBackButton
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleGeneralActionSelection
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleSwitchPokemonSelection
import com.cobblemon.mod.common.client.gui.battle.subscreen.ForfeitConfirmationSelection
import com.cobblemon.mod.common.client.gui.battle.widgets.BattleMessagePane
import com.cobblemon.mod.common.client.keybind.boundKey
import com.cobblemon.mod.common.client.keybind.keybinds.PartySendBinding
import com.cobblemon.mod.common.client.render.drawScaledText
import com.cobblemon.mod.common.net.messages.server.battle.RemoveSpectatorPacket
import com.cobblemon.mod.common.util.battleLang
import com.cobblemon.mod.common.util.cobblemonResource
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.util.math.MatrixStack
class BattleGUI : Screen(battleLang("gui.title")) {
    companion object {
        const val OPTION_VERTICAL_SPACING = 3
        const val OPTION_HORIZONTAL_SPACING = 3
        const val OPTION_ROOT_X = 12
        const val OPTION_VERTICAL_OFFSET = 85

        val fightResource = cobblemonResource("textures/gui/battle/battle_menu_fight.png")
        val bagResource = cobblemonResource("textures/gui/battle/battle_menu_bag.png")
        val switchResource = cobblemonResource("textures/gui/battle/battle_menu_switch.png")
        val runResource = cobblemonResource("textures/gui/battle/battle_menu_run.png")
    }

    private lateinit var messagePane: BattleMessagePane
    var opacity = 0F
    val actor = CobblemonClient.battle?.side1?.actors?.find { it.uuid == MinecraftClient.getInstance().player?.uuid }
    val specBackButton = BattleBackButton(12f, MinecraftClient.getInstance().window.scaledHeight - 32f)

    var queuedActions = mutableListOf<() -> Unit>()

    override fun init() {
        super.init()
        messagePane = BattleMessagePane(CobblemonClient.battle!!.messages)
        addDrawableChild(messagePane)
    }

    fun changeActionSelection(newSelection: BattleActionSelection?) {
        val current = children().find { it is BattleActionSelection }
        queuedActions.add {
            current?.let(this::remove)
            if (newSelection != null) {
                addDrawableChild(newSelection)
            }
        }
    }

    fun getCurrentActionSelection() = children().filterIsInstance<BattleActionSelection>().firstOrNull()

    fun removeInvalidBattleActionSelection() {
        children().filterIsInstance<BattleActionSelection>().firstOrNull()?.let {
            children().remove(it)
        }
    }

    fun selectAction(request: SingleActionRequest, response: ShowdownActionResponse) {
        val battle = CobblemonClient.battle ?: return
        if (request.response == null) {
            request.response = response
            changeActionSelection(null)
            battle.checkForFinishedChoosing()
        }
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        opacity = CobblemonClient.battleOverlay.opacityRatio.toFloat()
        children().filterIsInstance<BattleMessagePane>().forEach { it.opacity = opacity.coerceAtLeast(0.3F) }

        queuedActions.forEach { it() }
        queuedActions.clear()
        super.render(context, mouseX, mouseY, delta)
        val battle = CobblemonClient.battle
        if (battle == null) {
            close()
            return
        } else if (CobblemonClient.battleOverlay.opacityRatio <= 0.1 && CobblemonClient.battle?.minimised == true) {
            close()
            return
        }

        if (actor != null) {
            if (battle.mustChoose) {
                if (getCurrentActionSelection() == null) {
                    val unanswered = battle.getFirstUnansweredRequest()
                    if (unanswered != null) {
                        changeActionSelection(deriveRootActionSelection(actor, unanswered))
                    }
                }
            } else if (getCurrentActionSelection() != null) {
                changeActionSelection(null)
            }
        }

        if (battle.spectating) {
            specBackButton.render(context.matrices, mouseX, mouseY, delta)
        }

        val currentSelection = getCurrentActionSelection()
        if (currentSelection == null || currentSelection is BattleGeneralActionSelection ) {
            drawScaledText(
                context = context,
                text = battleLang("ui.hide_label", PartySendBinding.boundKey().localizedText),
                x = MinecraftClient.getInstance().window.scaledWidth / 2,
                y = (MinecraftClient.getInstance().window.scaledHeight / 5),
                opacity = 0.75F * opacity,
                centered = true
            )
        } else if (currentSelection is ForfeitConfirmationSelection) {
            drawScaledText(
                context = context,
                text = battleLang("ui.forfeit_confirmation", PartySendBinding.boundKey().localizedText),
                x = MinecraftClient.getInstance().window.scaledWidth / 2,
                y = (MinecraftClient.getInstance().window.scaledHeight / 5),
                opacity = 0.75F * opacity,
                centered = true
            )
        }


        queuedActions.forEach { it() }
        queuedActions.clear()
    }

    fun deriveRootActionSelection(actor: ClientBattleActor, request: SingleActionRequest): BattleActionSelection {
        return if (request.forceSwitch) {
            BattleSwitchPokemonSelection(this, request)
        } else {
            BattleGeneralActionSelection(this, request)
        }
    }

    override fun shouldPause() = false
    override fun close() {
        super.close()
        CobblemonClient.battle?.minimised = true
        PartySendBinding.canApplyChange = false
        PartySendBinding.wasDown = true
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, deltaX: Double, deltaY: Double): Boolean {
        if (this::messagePane.isInitialized) messagePane.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)
    }

    override fun charTyped(chr: Char, modifiers: Int): Boolean {
        if (chr.toString().equals(PartySendBinding.boundKey().localizedText.string, ignoreCase = true) && CobblemonClient.battleOverlay.opacity == BattleOverlay.MAX_OPACITY && PartySendBinding.canAction()) {
            val battle = CobblemonClient.battle ?: return false
            battle.minimised = !battle.minimised
            PartySendBinding.actioned()
            return true
        }
        return super.charTyped(chr, modifiers)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val battle = CobblemonClient.battle
        if (battle?.spectating == true && specBackButton.isHovered(mouseX, mouseY)) {
            RemoveSpectatorPacket(battle.battleId).sendToServer()
            CobblemonClient.endBattle()
        }
        return super.mouseClicked(mouseX, mouseY, button)
    }
}