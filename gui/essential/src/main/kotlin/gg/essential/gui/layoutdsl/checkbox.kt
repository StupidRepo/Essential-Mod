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
package gg.essential.gui.layoutdsl

import gg.essential.elementa.components.inspector.Inspector
import gg.essential.gui.EssentialPalette
import gg.essential.gui.common.Checkbox
import gg.essential.gui.elementa.state.v2.MutableState
import gg.essential.universal.USound
import gg.essential.util.onLeftClick

fun LayoutScope.checkbox(
    initialValue: Boolean,
    onValueChange: (Boolean) -> Unit
): Checkbox {
    return Checkbox(initialValue)().apply {
        isChecked.onSetValue {
            onValueChange(it)
        }
    }
}

fun LayoutScope.checkbox(
    initialValue: Boolean,
): Checkbox {
    return Checkbox(initialValue)()
}

// Alternative checkbox style used in modern modals
fun LayoutScope.checkboxAlt(selected: MutableState<Boolean>, modifier: Modifier = Modifier) {
    val selectedModifier = Modifier.color(EssentialPalette.LINK).hoverColor(EssentialPalette.LINK_HIGHLIGHT)
    val outlineColorModifier = Modifier.whenTrue(selected, selectedModifier, Modifier.color(EssentialPalette.CHECKBOX_OUTLINE))
    val innerColorModifier = Modifier.whenTrue(selected, selectedModifier, Modifier.color(EssentialPalette.BUTTON).hoverColor(EssentialPalette.BUTTON_HIGHLIGHT))

    box(Modifier.width(9f).heightAspect(1f).hoverScope() then outlineColorModifier then modifier) {
        box(Modifier.width(7f).heightAspect(1f) then innerColorModifier) {
            if_(selected) {
                image(EssentialPalette.CHECKMARK_7X5, Modifier.color(EssentialPalette.TEXT_HIGHLIGHT))
            }
        }
    }.onLeftClick {
        it.stopPropagation()
        USound.playButtonPress()
        selected.set { !it }
    }
}

@Suppress("unused")
private val init = run {
    Inspector.registerComponentFactory(null)
}
