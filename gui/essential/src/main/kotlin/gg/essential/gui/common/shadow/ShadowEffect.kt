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
package gg.essential.gui.common.shadow

import gg.essential.elementa.components.UIBlock
import gg.essential.elementa.components.UIContainer
import gg.essential.elementa.components.UIImage
import gg.essential.elementa.constraints.CenterConstraint
import gg.essential.elementa.effects.Effect
import gg.essential.elementa.state.BasicState
import gg.essential.elementa.state.State
import gg.essential.gui.EssentialPalette
import gg.essential.gui.common.LoadingIcon
import gg.essential.gui.common.SequenceAnimatedUIImage
import gg.essential.universal.UGraphics
import gg.essential.universal.UMatrixStack
import java.awt.Color


class ShadowEffect(
    shadowColor: Color = EssentialPalette.COMPONENT_BACKGROUND
) : Effect() {

    private val shadowColorState = BasicState(shadowColor).map { it }

    override fun beforeDraw(matrixStack: UMatrixStack) {
        when (val boundComponent = boundComponent) {
            is EssentialUIText -> {

                // Copied from UIText
                val text = boundComponent.getText()

                // Don't draw if the boundComponent wouldn't draw
                if (text.isEmpty() || boundComponent.getColor().alpha <= 10) {
                    return
                }

                val constraints = boundComponent.constraints
                val scale = constraints.getTextScale()
                val fontProvider = constraints.fontProvider
                val x = boundComponent.getLeft()
                val y = boundComponent.getTop() + (if (constraints.y is CenterConstraint) fontProvider.getBelowLineHeight() * scale else 0f)
                val color = shadowColorState.get()

                UGraphics.enableBlend()

                fontProvider.drawString(
                    matrixStack,
                    text, color, x + 1, y + 1,
                    10f, scale, false
                )
            }
            is SequenceAnimatedUIImage -> {
                val child = boundComponent.currentFrameComponent ?: return
                child.drawImage(
                    matrixStack,
                    boundComponent.getLeft() + 1.0,
                    boundComponent.getTop() + 1.0,
                    boundComponent.getWidth().toDouble(),
                    boundComponent.getHeight().toDouble(),
                    shadowColorState.get()
                )
            }
            is UIBlock, is UIContainer -> {
                // Don't draw if the boundComponent wouldn't draw
                if (boundComponent.getColor().alpha == 0) {
                    return
                }
                val x = boundComponent.getLeft().toDouble()
                val y = boundComponent.getTop().toDouble()
                val x2 = boundComponent.getRight().toDouble()
                val y2 = boundComponent.getBottom().toDouble()

                val color = shadowColorState.get()

                UIBlock.drawBlock(matrixStack, color, x+1, y+1, x2+1, y2+1)
            }
            is UIImage -> {
                boundComponent.drawImage(
                    matrixStack,
                    boundComponent.getLeft() + 1.0,
                    boundComponent.getTop() + 1.0,
                    boundComponent.getWidth().toDouble(),
                    boundComponent.getHeight().toDouble(),
                    shadowColorState.get()
                )
            }
            is LoadingIcon -> {
                val xCenter = (boundComponent.getLeft() + boundComponent.getRight()) / 2
                val yCenter = (boundComponent.getTop() + boundComponent.getBottom()) / 2

                LoadingIcon.draw(
                    matrixStack,
                    xCenter + boundComponent.scale.toInt(),
                    yCenter + boundComponent.scale.toInt(),
                    boundComponent.scale,
                    boundComponent.time,
                    shadowColorState.get()
                )
            }
            else -> {
                throw UnsupportedOperationException("Shadow effect cannot be applied to ${getDebugInfo()}")
            }
        }
    }

    fun rebindColor(state: State<Color>) = apply {
        shadowColorState.rebind(state)
    }

    private fun getDebugInfo(): String {
        return boundComponent.componentName + " " + boundComponent.javaClass.name
    }
}