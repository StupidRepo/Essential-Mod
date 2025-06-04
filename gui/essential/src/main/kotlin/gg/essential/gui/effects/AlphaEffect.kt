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
package gg.essential.gui.effects

import gg.essential.elementa.effects.Effect
import gg.essential.gui.elementa.state.v2.State
import gg.essential.gui.elementa.state.v2.toV2
import gg.essential.universal.UGraphics
import gg.essential.universal.UMatrixStack
import gg.essential.universal.UResolution
import gg.essential.universal.render.URenderPipeline
import gg.essential.universal.shader.BlendState
import gg.essential.universal.vertex.UBufferBuilder
import gg.essential.util.GuiEssentialPlatform.Companion.platform
import gg.essential.util.image.GpuTexture
import java.io.Closeable
import java.lang.ref.PhantomReference
import java.lang.ref.ReferenceQueue
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Applies an alpha value to a component. This is done by snapshotting the framebuffer behind the component,
 * rendering the component, then rendering the snapshot with the inverse of the desired alpha.
 */
class AlphaEffect(private val alphaState: State<Float>) : Effect() {
    constructor(alphaState: gg.essential.elementa.state.State<Float>) : this(alphaState.toV2())

    private lateinit var resources: Resources

    override fun setup() {
        Resources.drainCleanupQueue()
        resources = Resources(this)
    }

    override fun beforeDraw(matrixStack: UMatrixStack) {
        if (!::resources.isInitialized) error("AlphaEffect has not yet been setup or has already been cleaned up! ElementaVersion.V4 or newer is required for proper operation!")

        val scale = UResolution.scaleFactor

        // Get the coordinates of the component within the bounds of the screen in real pixels
        val left = (boundComponent.getLeft() * scale).toInt().coerceIn(0..UResolution.viewportWidth)
        val right = (boundComponent.getRight() * scale).toInt().coerceIn(0..UResolution.viewportWidth)
        val top = (boundComponent.getTop() * scale).toInt().coerceIn(0..UResolution.viewportHeight)
        val bottom = (boundComponent.getBottom() * scale).toInt().coerceIn(0..UResolution.viewportHeight)

        val x = left
        val y = UResolution.viewportHeight - bottom // OpenGL screen coordinates start in the bottom left
        val width = right - left
        val height = bottom - top

        if (width == 0 || height == 0) {
            return
        }

        if (resources.texture.width != width || resources.texture.height != height) {
            resources.texture.resize(width, height)
        }
        resources.texture.copyFrom(listOf(GpuTexture.CopyOp(
            platform.mcFrameBufferColorTexture, x, y, 0, 0, width, height
        )))
    }

    override fun afterDraw(matrixStack: UMatrixStack) {
        // Get the coordinates of the component within the bounds of the screen in fractional MC pixels
        val left = boundComponent.getLeft().toDouble().coerceIn(0.0..UResolution.viewportWidth / UResolution.scaleFactor)
        val right = boundComponent.getRight().toDouble().coerceIn(0.0..UResolution.viewportWidth / UResolution.scaleFactor)
        val top = boundComponent.getTop().toDouble().coerceIn(0.0..UResolution.viewportHeight / UResolution.scaleFactor)
        val bottom = boundComponent.getBottom().toDouble().coerceIn(0.0..UResolution.viewportHeight / UResolution.scaleFactor)

        val x = left
        val y = top
        val width = right - left
        val height = bottom - top

        if (width == 0.0 || height == 0.0) {
            return
        }

        val red = 1f
        val green = 1f
        val blue = 1f
        val alpha = 1f - alphaState.get()

        val worldRenderer = UBufferBuilder.create(UGraphics.DrawMode.QUADS, UGraphics.CommonVertexFormats.POSITION_TEXTURE_COLOR)
        worldRenderer.pos(matrixStack, x, y + height, 0.0).tex(0.0, 0.0).color(red, green, blue, alpha).endVertex()
        worldRenderer.pos(matrixStack, x + width, y + height, 0.0).tex(1.0, 0.0).color(red, green, blue, alpha).endVertex()
        worldRenderer.pos(matrixStack, x + width, y, 0.0).tex(1.0, 1.0).color(red, green, blue, alpha).endVertex()
        worldRenderer.pos(matrixStack, x, y, 0.0).tex(0.0, 1.0).color(red, green, blue, alpha).endVertex()
        worldRenderer.build()?.drawAndClose(PIPELINE) {
            texture("u_Texture", resources.texture.glId)
        }
    }

    fun cleanup() {
        resources.close()
    }

    private class Resources(effect: AlphaEffect) : PhantomReference<AlphaEffect>(effect, referenceQueue), Closeable {
        val texture: GpuTexture = platform.newGpuTexture(1, 1, GpuTexture.Format.RGBA8)

        init {
            toBeCleanedUp.add(this)
        }

        private var closed = false

        override fun close() {
            if (closed) return
            closed = true

            toBeCleanedUp.remove(this)

            texture.delete()
        }

        companion object {
            val referenceQueue = ReferenceQueue<AlphaEffect>()
            val toBeCleanedUp: MutableSet<Resources> = Collections.newSetFromMap(ConcurrentHashMap())

            fun drainCleanupQueue() {
                while (true) {
                    ((referenceQueue.poll() ?: break) as Resources).close()
                }
            }
        }
    }

    companion object {
        private val PIPELINE: URenderPipeline
        init {
            val vertexShaderSource = """
                #version 110
    
                varying vec2 f_Position;
                varying vec2 f_TexCoord;
    
                void main() {
                    f_Position = gl_Vertex.xy;
                    f_TexCoord = gl_MultiTexCoord0.st;
    
                    gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
                    gl_FrontColor = gl_Color;
                }
            """.trimIndent()
            val fragmentShaderSource = """
                #version 110
    
                uniform sampler2D u_Texture;
    
                varying vec2 f_Position;
                varying vec2 f_TexCoord;
    
                void main() {
                    gl_FragColor = gl_Color * vec4(texture2D(u_Texture, f_TexCoord).rgb, 1.0);
                }
            """.trimIndent()
            PIPELINE = URenderPipeline.builderWithLegacyShader(
                "elementa:alpha_effect",
                UGraphics.DrawMode.QUADS,
                UGraphics.CommonVertexFormats.POSITION_TEXTURE_COLOR,
                vertexShaderSource,
                fragmentShaderSource,
            ).apply {
                blendState = BlendState.NORMAL
            }.build()
        }
    }
}