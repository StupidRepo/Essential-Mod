/*
 * Copyright (c) 2024 ModCore Inc. All rights reserved.
 *
 * This code is part of ModCore Inc.'s Essential Mod repository and is protected
 * under copyright registration # TX0009138511. For the full license, see:
 * https://github.com/EssentialGG/Essential/blob/main/LICENSE
 *
 * You may not use, copy, reproduce, modify, sell, license, distribute,
 * commercialize, or otherwise exploit, or create derivative works based
 * upon, this file or any other in this repository, all of which is reserved by Essential.
 */
package gg.essential.network.connectionmanager.suspension

import gg.essential.Essential
import gg.essential.gui.InternalEssentialGUI
import gg.essential.gui.modals.SuspensionModal
import gg.essential.gui.overlay.ModalFlow
import gg.essential.network.connectionmanager.ConnectionManager
import gg.essential.network.connectionmanager.social.Suspension
import gg.essential.universal.UMinecraft
import gg.essential.util.GuiUtil
import gg.essential.util.isMainMenu
import net.minecraft.client.gui.GuiIngameMenu

class McSuspensionManager(connectionManager: ConnectionManager) : SuspensionManager(connectionManager) {

    init {
        Essential.EVENT_BUS.register(this)
    }

    override fun isSuspensionShowable(): Boolean {
        val openedScreen = GuiUtil.openedScreen()
        return openedScreen is InternalEssentialGUI || openedScreen.isMainMenu || openedScreen is GuiIngameMenu
    }

    override fun showSuspension(suspension: Suspension) {
        GuiUtil.launchModalFlow {
            suspensionModal(suspension)
        }
    }
}

suspend fun ModalFlow.suspensionModal(suspension: Suspension) {
    val connectionManager = Essential.getInstance().connectionManager
    val suspensionManager = connectionManager.suspensionManager
    awaitModal { continuation ->
        suspensionManager.markSeen()
        SuspensionModal(
            modalManager,
            suspension,
            connectionManager.chatManager.getReportReasons(UMinecraft.getSettings().language),
            continuation
        )
    }
}