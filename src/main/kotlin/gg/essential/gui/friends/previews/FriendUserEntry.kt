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
package gg.essential.gui.friends.previews

import gg.essential.elementa.constraints.SiblingConstraint
import gg.essential.elementa.dsl.childOf
import gg.essential.elementa.dsl.constrain
import gg.essential.elementa.dsl.provideDelegate
import gg.essential.elementa.state.BasicState
import gg.essential.elementa.state.toConstraint
import gg.essential.gui.EssentialPalette
import gg.essential.gui.common.ContextOptionMenu
import gg.essential.gui.common.or
import gg.essential.gui.friends.SocialMenu
import gg.essential.gui.util.hoveredState
import gg.essential.vigilance.utils.onLeftClick
import java.util.*

class FriendUserEntry(
    gui: SocialMenu,
    user: UUID,
    sortListener: SortListener
) : BasicUserEntry(user, EssentialPalette.BURGER_7X5, EssentialPalette.TEXT_HIGHLIGHT, sortListener) {

    private val friendStatus by FriendStatus(user, gui.socialStateManager.statusStates, sortListener).constrain {
        y = SiblingConstraint(5f)
    } childOf textContainer

    private val dropdownOpen = BasicState(false)

    init {
        button.setColor(EssentialPalette.getButtonColor(button.hoveredState() or dropdownOpen).toConstraint())
        button.onLeftClick {
            dropdownOpen.set(true)
            gui.showUserDropdown(user, ContextOptionMenu.Position(button, false)) {
                dropdownOpen.set(false)
            }
            it.stopPropagation()
        }
        onMouseClick {
            if (it.mouseButton > 1) {
                return@onMouseClick
            }
            gui.showUserDropdown(user, ContextOptionMenu.Position(it)) {

            }
        }
    }
}
