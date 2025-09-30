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
package gg.essential.model

import dev.folomeev.kotgl.matrix.vectors.Vec2
import dev.folomeev.kotgl.matrix.vectors.Vec3
import dev.folomeev.kotgl.matrix.vectors.dot
import dev.folomeev.kotgl.matrix.vectors.mutables.cross
import dev.folomeev.kotgl.matrix.vectors.mutables.crossSelf
import dev.folomeev.kotgl.matrix.vectors.mutables.div
import dev.folomeev.kotgl.matrix.vectors.mutables.minus
import dev.folomeev.kotgl.matrix.vectors.mutables.mutableVec2
import dev.folomeev.kotgl.matrix.vectors.mutables.mutableVec3
import dev.folomeev.kotgl.matrix.vectors.mutables.normalize
import dev.folomeev.kotgl.matrix.vectors.mutables.normalizeSelf
import dev.folomeev.kotgl.matrix.vectors.mutables.plus
import dev.folomeev.kotgl.matrix.vectors.mutables.plusScaled
import dev.folomeev.kotgl.matrix.vectors.mutables.plusScaledSelf
import dev.folomeev.kotgl.matrix.vectors.mutables.plusSelf
import dev.folomeev.kotgl.matrix.vectors.mutables.set
import dev.folomeev.kotgl.matrix.vectors.mutables.times
import dev.folomeev.kotgl.matrix.vectors.mutables.timesSelf
import dev.folomeev.kotgl.matrix.vectors.mutables.toMutable
import dev.folomeev.kotgl.matrix.vectors.sqrLength
import dev.folomeev.kotgl.matrix.vectors.vec2
import dev.folomeev.kotgl.matrix.vectors.vec3
import dev.folomeev.kotgl.matrix.vectors.vecUnitX
import dev.folomeev.kotgl.matrix.vectors.vecUnitY
import dev.folomeev.kotgl.matrix.vectors.vecUnitZ
import dev.folomeev.kotgl.matrix.vectors.vecZero
import gg.essential.model.backend.RenderBackend
import gg.essential.model.collision.CollisionProvider
import gg.essential.model.file.ParticleEffectComponents
import gg.essential.model.file.ParticleEffectComponents.ParticleAppearanceBillboard.Direction.Custom
import gg.essential.model.file.ParticleEffectComponents.ParticleAppearanceBillboard.Direction.FromVelocity
import gg.essential.model.file.ParticleEffectComponents.ParticleAppearanceBillboard.FacingCameraMode.*
import gg.essential.model.file.ParticlesFile
import gg.essential.model.molang.MolangContext
import gg.essential.model.molang.MolangQuery
import gg.essential.model.util.Color
import gg.essential.model.light.Light
import gg.essential.model.light.LightProvider
import gg.essential.model.molang.Molang
import gg.essential.model.molang.MolangQueryEntity
import gg.essential.model.molang.MolangQueryTime
import gg.essential.model.molang.Variables
import gg.essential.model.molang.VariablesMap
import gg.essential.model.util.Quaternion
import gg.essential.model.util.UMatrixStack
import gg.essential.model.util.UVertexConsumer
import gg.essential.model.util.rotateBy
import gg.essential.model.util.rotateSelfBy
import java.util.UUID
import kotlin.math.PI
import kotlin.math.absoluteValue
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.random.nextInt

/**
 * A sub-system for managing and rendering particles.
 *
 * The two fundamental entities of this system are [Particle]s and [Emitter]s.
 *
 * Emitters are invisible and remain fixed at one position and rotation (relative to a [Locator]) where they emit
 * particles and/or events (which can spawn other emitters, particles, sounds, etc.).
 *
 * Particles on the other hand will move around depending on their configuration and can be visible in the form of
 * billboards. They too can emit events at their position.
 *
 * Every particles has a corresponding emitter that spawned it, even when it was spawned as a one-shot particle (in this
 * case the emitter is just never active and immediately marked as dead).
 * How an emitter and its corresponding particles behave is configured by their [ParticleEffect] (corresponds to one
 * particles json file). Different emitters can have different configuration but all particles share the configuration
 * of their respective emitter.
 *
 * For practical purposes, a particle system is sub-divided into separate [Universe]s each of which has a different
 * source of time. This is so particles spawned by one cosmetic can play slightly faster so they can catch up with the
 * remote client that spawned them, similar to how cosmetic animations slowly catch up to be in sync as well.
 */
class ParticleSystem(
    private val random: Random,
    private val collisionProvider: CollisionProvider,
    private val lightProvider: LightProvider,
    private val playSound: (ModelAnimationState.SoundEvent) -> Unit,
) {
    private val universes = mutableMapOf<MolangQueryTime, Universe>()

    private val billboardRenderPasses = mutableMapOf<ParticleEffect.RenderPass, MutableSet<Particle>>()

    private inner class Universe(
        val timeSource: MolangQueryTime,
    ) {
        var lastUpdate: Float = timeSource.time

        val emitters = mutableListOf<Emitter?>()
        val particles = mutableListOf<Particle?>()

        fun addParticle(particle: Particle) {
            particles.add(particle)

            val effect = particle.emitter.effect
            val renderPass = particle.renderPass
            if (renderPass != null) {
                if (effect.components.particleAppearanceBillboard != null) {
                    billboardRenderPasses.getOrPut(renderPass, ::mutableSetOf).add(particle)
                }
            }
        }

        fun removeParticleAt(index: Int): Particle {
            val particle = particles.set(index, null) ?: throw IndexOutOfBoundsException()

            val effect = particle.emitter.effect
            val renderPass = particle.renderPass
            if (renderPass != null) {
                if (effect.components.particleAppearanceBillboard != null) {
                    val renderPassSet = billboardRenderPasses.getValue(renderPass)
                    renderPassSet.remove(particle)
                    if (renderPassSet.isEmpty()) {
                        billboardRenderPasses.remove(renderPass)
                    }
                }
            }

            return particle
        }
    }

    fun spawn(event: ModelAnimationState.ParticleEvent) {
        val universe = universes.getOrPut(event.timeSource) { Universe(event.timeSource) }
        val (position, rotation) = event.locator.positionAndRotation // more performant to get together
        val emitter = Emitter(
            this,
            universe,
            event.effect,
            event.textureSource,
            event.sourceEntity,
            position,
            rotation,
            vec3(),
            event.locator,
            vec3(),
        )
        event.preEffectScript?.eval(emitter.molang)
        universe.emitters.add(emitter)

        val dt = (universe.lastUpdate - event.time).coerceAtLeast(0f)
        emitter.startLoop(dt)

        // Spawn particles for at most the last few seconds, anything before that is probably already gone and we need
        // *some* limit on the total simulation time to avoid effective live-locks on large values
        val maxSimTime = 10f
        if (dt > maxSimTime) {
            emitter.skip(dt - maxSimTime)
            emitter.update(maxSimTime)
        } else {
            emitter.update(dt)
        }
    }

    fun update() {
        var cleanup = false
        for (universe in universes.values) {
            update(universe)

            if (universe.emitters.isEmpty() && universe.particles.isEmpty()) {
                cleanup = true
            }
        }
        if (cleanup) {
            universes.values.removeAll { it.emitters.isEmpty() && it.particles.isEmpty() }
        }
    }

    private fun update(universe: Universe) {
        val now = universe.timeSource.time
        val dt = now - universe.lastUpdate
        universe.lastUpdate = now

        val emitters = universe.emitters
        val particles = universe.particles

        // Before all else, get the current last index for emitters and particles
        // This allows us to differentiate which particles were already present before this update and which ones were
        // only added during this update step. This is important because only the former should receive an `update` call
        // for `dt`, the latter do not yet live that long and will receive an `update` call for the appropriate fraction
        // of `dt` from whoever spawned them.
        // When an emitter/particle expires, we also won't simply remove it from the list, as that would mess with
        // the indices (and result in horrible performance if we need to shift a large array of data by one each time).
        // Instead we set the entry to `null` and then compress the array only at the end of the update call.
        val lastEmitterIndex = emitters.lastIndex
        val lastParticleIndex = particles.lastIndex

        // How many expired ones there are currently present in the lists.
        // If this becomes large enough, we'll re-compress the lists.
        var expiredEmitters = 0
        var expiredParticles = 0

        // Main update loops
        // We iterate via indices because we explicitly allow modification (see comment at start of this method).
        for (i in 0..lastEmitterIndex) {
            val emitter = emitters[i]
            if (emitter != null) {
                if (emitter.update(dt)) {
                    continue
                }
                emitters[i] = null
            }
            expiredEmitters++
        }
        for (i in 0..lastParticleIndex) {
            val particle = particles[i]
            if (particle != null) {
                if (particle.update(dt)) {
                    continue
                }
                universe.removeParticleAt(i)
                particle.emitter.activeParticles--
            }
            expiredParticles++
        }

        // Finally, if there's enough holes in the lists, re-compress them in a single pass
        if (expiredEmitters > 64 || expiredEmitters == emitters.size) {
            emitters.removeAll { it == null }
        }
        if (expiredParticles > 64 || expiredParticles == particles.size) {
            particles.removeAll { it == null }
        }
    }

    fun isEmpty(): Boolean = universes.isEmpty()

    fun hasAnythingToRender(): Boolean =
        billboardRenderPasses.isNotEmpty()

    fun render(
        matrixStack: UMatrixStack,
        cameraPos: Vec3,
        cameraRot: Quaternion,
        particleVertexConsumerProvider: VertexConsumerProvider,
        cameraUuid: UUID,
        cameraFirstPerson: Boolean,
        hideParticlesInFirstPerson: Boolean,
        onlyRenderFromSource: UUID? = null,
    ) {
        val cameraFacing = vec3(0f, 0f, -1f).rotateBy(cameraRot)
        for ((renderPass, allParticles) in billboardRenderPasses.entries.sortedBy { it.key.material.needsSorting }) {

            // filter out unwanted particles prior to depth sorting calculations
            val particles = if (onlyRenderFromSource != null) {
                val particlesFromSource = allParticles.filter { it.emitter.sourceEntity.uuid == onlyRenderFromSource }
                if (particlesFromSource.isEmpty()) continue
                particlesFromSource
            } else {
                allParticles // never empty
            }

            particleVertexConsumerProvider.provide(renderPass) { vertexConsumer ->
                if (renderPass.material.needsSorting) {
                    for (particle in particles) {
                        particle.prepareBillboard(cameraPos, cameraRot)

                        val billboardNormal = mutableVec3(0f, 0f, -1f).rotateSelfBy(particle.billboardRotation)
                        particle.distance = cameraPos.minus(particle.billboardPosition).dot(billboardNormal)
                    }
                    for (particle in particles.sortedByDescending { it.distance }) {
                        particle.renderBillboard(matrixStack, vertexConsumer, cameraFacing, cameraUuid, cameraFirstPerson, hideParticlesInFirstPerson)
                    }
                } else {
                    for (particle in particles) {
                        particle.prepareBillboard(cameraPos, cameraRot)
                        particle.renderBillboard(matrixStack, vertexConsumer, cameraFacing, cameraUuid, cameraFirstPerson, hideParticlesInFirstPerson)
                    }
                }
            }
        }
    }

    private class Emitter(
        val system: ParticleSystem,
        val universe: Universe,
        val effectRef: ParticleEffectWithReferencedEffects,
        val textureSource: () -> RenderBackend.Texture?,
        val sourceEntity: MolangQueryEntity,
        /** Global position of the emitter. Updated each frame if alive and bound to a locator. */
        var position: Vec3,
        /** Global rotation of the emitter. Updated each frame if alive and bound to a locator. */
        var rotation: Quaternion,
        /** Global velocity of the emitter. Updated each frame if alive and bound to a locator. */
        var velocity: Vec3,
        /** The locator which this emitter is bound to. */
        val locator: Locator?,
        /** Offset of this emitter from the locator position in local space. */
        val locatorOffset: Vec3?,
    ) {

        val effect = effectRef.particleEffect
        private val components = effect.components
        private val curveVariables = CurveVariables({ molang }, effect.curves)
        private val variables = VariablesMap().fallbackBackTo(curveVariables)
        val molang: MolangContext = MolangContext(MolangQuery.Empty, variables)

        init {
            variables["entity_scale"] = 1f
            components.emitterInitialization?.creationExpression?.eval(molang)
        }

        var activeParticles = 0

        private var firedCreationEvents = false
        private var firedExpirationEvents = false

        private var age: Float by variables.getOrPut("emitter_age", 0f)

        private var activeTime: Float by variables.getOrPut("emitter_lifetime", 0f)

        private var sleepTime: Float = 0f

        /** No particles will be spawned unless [activeParticles] is smaller than this value. */
        private var maxParticles: Float = 0f

        /** No particle will be spawned until this reaches 0. */
        private var cooldownTime: Float = 0f

        /** The next timeline event that will be emitted during an [update] call once its time has come. */
        private var nextTimelineEvent: Map.Entry<Float, List<String>>? = null

        fun startLoop(timeSince: Float) {
            for (i in 1..4) {
                variables["emitter_random_$i"] = system.random.nextFloat()
            }

            age = 0f

            activeTime = components.emitterLifetimeLooping?.activeTime?.eval(molang)
                ?: components.emitterLifetimeOnce?.activeTime?.eval(molang)
                ?: Float.POSITIVE_INFINITY
            sleepTime = components.emitterLifetimeLooping?.sleepTime?.eval(molang)
                ?: 0f

            repeat(components.emitterRateInstant?.numParticles?.eval(molang)?.toInt() ?: 0) {
                emit(timeSince)
            }

            maxParticles = components.emitterRateSteady?.maxParticles?.eval(molang)
                ?: Float.POSITIVE_INFINITY

            cooldownTime = components.emitterRateSteady?.spawnRate?.eval(molang)?.let { 1 / it }
                ?: Float.POSITIVE_INFINITY

            if (!firedCreationEvents) {
                firedCreationEvents = true
                fire(timeSince, components.emitterLifetimeEvents.creationEvents, null)
            }

            nextTimelineEvent = components.emitterLifetimeEvents.timeline.lowestEntry()
        }

        fun skip(dt: Float) {
            age += dt
        }

        fun update(dt: Float): Boolean {
            val alive = doUpdate(dt)

            if (!alive && !firedExpirationEvents) {
                firedExpirationEvents = true
                fire(0f, components.emitterLifetimeEvents.expirationEvents, null)
            }

            return alive
        }

        private fun doUpdate(dt: Float): Boolean {
            age += dt

            curveVariables.update()

            components.emitterInitialization?.perUpdateExpression?.eval(molang)

            if (locator != null) {
                // more performant to get them together
                val posRot = locator.positionAndRotation
                position = posRot.first
                rotation = posRot.second
                velocity = locator.velocity
                if (locatorOffset != null) {
                    position = position.plus(locatorOffset.rotateBy(rotation))
                }

                if (!locator.isValid) {
                    return false
                }
            }

            fireTimelineEvents()

            components.emitterLifetimeExpression?.let { config ->
                if (config.expirationExpression.eval(molang) != 0f) {
                    return false
                }

                if (config.activationExpression.eval(molang) == 0f) {
                    return true
                }
            }

            if (age > activeTime) {
                if (components.emitterLifetimeOnce != null) {
                    return false
                }
                val timeSinceNewLoop = age - activeTime - sleepTime
                if (timeSinceNewLoop < 0) {
                    return true
                }
                startLoop(timeSinceNewLoop)
            }

            cooldownTime -= dt
            while (cooldownTime < 0) {
                if (activeParticles < maxParticles) {
                    val timeSinceEmit = -cooldownTime
                    age -= timeSinceEmit
                    emit(timeSinceEmit)
                    cooldownTime += components.emitterRateSteady?.spawnRate?.eval(molang)?.let { 1 / it } ?: Float.POSITIVE_INFINITY
                    age += timeSinceEmit
                } else {
                    cooldownTime = 0f
                }
            }

            return true
        }

        fun emit(dt: Float, inheritVelocity: Boolean = false) {
            // Don't emit particle if the locator bone is not visible
            if (locator?.isVisible == false) return

            val localSpace = if (components.emitterLocalSpace?.position == true) locator else null

            val particle = Particle(this, localSpace)

            particle.emit(inheritVelocity)

            universe.addParticle(particle)
            particle.emitter.activeParticles++

            particle.update(dt)
        }

        private fun fireTimelineEvents() {
            while (true) {
                val (time, events) = nextTimelineEvent ?: return
                val timeSinceEvent = age - time
                if (timeSinceEvent < 0) return

                fire(timeSinceEvent, events, null)

                nextTimelineEvent = components.emitterLifetimeEvents.timeline.higherEntry(time)
            }
        }

        fun fire(timeSince: Float, events: List<String>, particle: Particle?) =
            events.forEach { fire(timeSince, it, particle) }

        fun fire(timeSince: Float, eventName: String, particle: Particle?) {
            val event = effect.events[eventName] ?: return
            fire(timeSince, event, particle)
        }

        fun fire(timeSince: Float, event: ParticlesFile.Event, particle: Particle?) {
            event.sequence?.forEach { fire(timeSince, it, particle) }

            event.randomize?.let { options ->
                val weights = options.sumOf { it.weight.toDouble() }
                var choice = system.random.nextFloat() * weights
                for (option in options) {
                    choice -= option.weight
                    if (choice <= 0) {
                        fire(timeSince, option.value, particle)
                        break
                    }
                }
            }

            event.expression?.let { expr ->
                age -= timeSince
                expr.eval(molang)
                age += timeSince
            }

            event.particle?.let { config ->
                val targetEffectRef = effectRef.getOtherParticleByReference(config.effect) ?: return@let
                // TODO: docs say about "particle" that we should be "creating the emitter if it doesn't already exist"
                //       but how are we supposed to determine whether an emitter "at the event location" already
                //       exists in the first place? just going to create a new one each time for now
                val targetEmitter = if (config.type.isBound && locator != null) {
                    Emitter(
                        system,
                        universe,
                        targetEffectRef,
                        textureSource,
                        sourceEntity,
                        particle?.globalPosition ?: position,
                        rotation,
                        particle?.globalVelocity ?: velocity,
                        locator,
                        particle?.globalPosition
                            ?.minus(locator.position)?.rotateSelfBy(locator.rotation.invert())
                            ?: locatorOffset,
                    )
                } else {
                    Emitter(
                        system,
                        universe,
                        targetEffectRef,
                        textureSource,
                        sourceEntity,
                        particle?.globalPosition ?: position,
                        Quaternion.Identity,
                        particle?.globalVelocity ?: velocity,
                        null,
                        null,
                    )
                }
                config.preEffectExpression.eval(targetEmitter.molang)
                if (config.type.isParticle) {
                    targetEmitter.emit(timeSince, config.type.inheritVelocity)
                } else {
                    universe.emitters.add(targetEmitter)
                    targetEmitter.startLoop(timeSince)
                    targetEmitter.update(timeSince)
                }
            }

            event.sound?.let { config ->
                val targetSound = effectRef.referencedSounds[config.event] ?: return@let
                system.playSound(ModelAnimationState.SoundEvent(
                    universe.timeSource,
                    universe.lastUpdate - timeSince,
                    sourceEntity,
                    targetSound,
                    if (particle != null) Particle.LocatorFor(particle) else LocatorFor(this),
                ))
            }
        }

        class LocatorFor(val emitter: Emitter) : Locator {
            override val parent: Locator?
                get() = emitter.locator
            override val isValid: Boolean
                get() = !emitter.firedExpirationEvents
            override val position: Vec3
                get() = emitter.position
            override val rotation: Quaternion
                get() = emitter.rotation
            override val velocity: Vec3
                get() = emitter.velocity
            override val isVisible: Boolean
                get() = parent?.isVisible ?: true
        }
    }

    private class Particle(
        val emitter: Emitter,
        /** This is the locator describing the space in which this particle is simulated if it is not global. */
        val localSpace: Locator?,
    ) {
        val renderPass = emitter.effect.renderPass(emitter.textureSource)
        private val components = emitter.effect.components
        val curveVariables = CurveVariables({ molang }, emitter.effect.curves)
        private val variables = VariablesMap()
            .fallbackBackTo(curveVariables)
            .fallbackBackTo(emitter.molang.variables)
        private val molang: MolangContext = MolangContext(MolangQuery.Empty, variables)

        private var firedCreationEvents = false
        private var firedExpirationEvents = false
        private var nextTimelineEvent: Map.Entry<Float, List<String>>? = null

        private var age: Float by variables.getOrPut("particle_age", 0f)
        private var lifetime: Float by variables.getOrPut("particle_lifetime", 0f)

        init {
            for (i in 1..4) {
                variables["particle_random_$i"] = emitter.system.random.nextFloat()
            }

            age = 0f
            lifetime = components.particleLifetimeExpression?.maxLifetime?.eval(molang) ?: 0f
            nextTimelineEvent = components.particleLifetimeEvents.timeline.lowestEntry()
        }

        /** Position of this particle. In local space if [localSpace] is given, otherwise in global space. */
        val position = mutableVec3()
        /** Velocity of this particle. In local space if [localSpace] is given, otherwise in global space. */
        val velocity = mutableVec3()
        /** Direction of this particle. In local space if [localSpace] is given, otherwise in global space. */
        val direction = mutableVec3()

        /** Position of this particle in global space. */
        val globalPosition: Vec3
            get() = if (localSpace != null) position.rotateBy(localSpace.rotation).plusSelf(localSpace.position) else position
        /** Velocity of this particle in global space. */
        val globalVelocity: Vec3
            get() = if (localSpace != null) velocity.rotateBy(localSpace.rotation) else velocity

        /** Rotation of the emitter when this particle was emitted. Undefined when [localSpace] is given. */
        val emitterRotationOnEmit = emitter.rotation

        var rotationAngle = components.particleInitialSpin?.rotation?.eval(molang) ?: 0f
        var rotationRate = components.particleInitialSpin?.rotationRate?.eval(molang) ?: 0f

        /** Stores the global position of the billboard (if any) of this particle. Valid only during rendering.*/
        var billboardPosition = vec3()
        /** Stores the global rotation of the billboard (if any) of this particle. Valid only during rendering. */
        var billboardRotation = Quaternion.Identity
        /** Temporary value used for sorting because Kotlin doesn't seem to have a `sort_by_cached_key`. */
        var distance: Float = 0f

        fun emit(inheritVelocity: Boolean) {
            var pos: Vec3 = vecZero()
            var dir: Vec3 = vecZero()

            fun ParticleEffectComponents.Direction.computeFor(point: Vec3): Vec3 =
                when (this) {
                    ParticleEffectComponents.Direction.Inwards -> point.times(-1f)
                    ParticleEffectComponents.Direction.Outwards -> point
                    is ParticleEffectComponents.Direction.Custom -> vec.eval(molang)
                }

            val random = emitter.system.random

            components.emitterShapePoint?.let { config ->
                pos = config.offset.eval(molang)

                val vec = mutableVec3()
                do {
                    vec.set(
                        (random.nextFloat() - 0.5f) * 2f,
                        (random.nextFloat() - 0.5f) * 2f,
                        (random.nextFloat() - 0.5f) * 2f,
                    )
                } while (vec.sqrLength().let { it > 1 || it == 0f })

                vec.normalizeSelf()

                dir = config.direction.computeFor(vec)
            }

            components.emitterShapeBox?.let { config ->
                val point = mutableVec3(
                    (random.nextFloat() - 0.5f) * 2f,
                    (random.nextFloat() - 0.5f) * 2f,
                    (random.nextFloat() - 0.5f) * 2f,
                )

                if (config.surfaceOnly) {
                    val side = random.nextInt(0..5)
                    val value = if (side > 2) 1f else -1f
                    when (side % 3) {
                        0 -> point.x = value
                        1 -> point.y = value
                        2 -> point.z = value
                    }
                }

                point.timesSelf(config.halfDimensions.eval(molang))
                pos = config.offset.eval(molang).plus(point)
                dir = config.direction.computeFor(point)
            }

            components.emitterShapeSphere?.let { config ->
                val vec = mutableVec3()
                do {
                    vec.set(
                        (random.nextFloat() - 0.5f) * 2f,
                        (random.nextFloat() - 0.5f) * 2f,
                        (random.nextFloat() - 0.5f) * 2f,
                    )
                } while (vec.sqrLength().let { it > 1 || it == 0f })

                if (config.surfaceOnly) {
                    vec.normalizeSelf()
                }

                vec.timesSelf(config.radius.eval(molang))
                pos = config.offset.eval(molang).plus(vec)
                dir = config.direction.computeFor(vec)
            }

            components.emitterShapeDisc?.let { config ->
                val radius = config.radius.eval(molang)
                val normal = config.planeNormal.eval(molang).normalize()

                // To get a uniformly distributed random point on the disc, we first compute an arbitrary unit vector
                // in the disc plane, then rotate that around the normal by a random amount and finally scale it by a
                // random amount.

                // We start with an arbitrary unit vector
                val vec = mutableVec3(1f, 0f, 0f)
                // just needs to be linearly independent from the normal; if it's not, choose a different one
                if (vec.dot(normal).absoluteValue > 0.9) {
                    vec.set(0f, 1f, 0f)
                }
                // then we can get an arbitrary unit vector in the disc plane via a simple cross product
                vec.crossSelf(normal).normalizeSelf()
                // now rotate around the normal to get a random unit vector in the disc plane
                vec.rotateSelfBy(Quaternion.fromAxisAngle(normal, random.nextFloat() * 2 * PI.toFloat()))
                // finally we just need to scale it by the radius and (optionally) uniformly distribute it across the
                // plane instead of just its rim
                vec.timesSelf(radius * if (config.surfaceOnly) 1f else sqrt(random.nextFloat()))

                pos = config.offset.eval(molang).plus(vec)
                dir = config.direction.computeFor(vec)
            }

            if (components.emitterLocalSpace?.rotation != true) {
                pos = pos.rotateBy(emitter.rotation)
                dir = dir.rotateBy(emitter.rotation)
            }

            if (localSpace == null) {
                pos = pos.plus(emitter.position)
            } else if (emitter.locatorOffset != null) {
                pos = pos.plus(emitter.locatorOffset)
            }

            position.set(pos)
            direction.set(dir).normalizeSelf()
            velocity.set(direction).timesSelf(components.particleInitialSpeed.eval(molang))

            if (inheritVelocity || components.emitterLocalSpace?.velocity == true) {
                velocity.plusSelf(emitter.velocity)
            }
        }

        fun update(dt: Float): Boolean {
            if (!firedCreationEvents) {
                firedCreationEvents = true
                emitter.fire(dt, components.particleLifetimeEvents.creationEvents, this)
            }

            val alive = doUpdate(dt)

            if (!alive && !firedExpirationEvents) {
                firedExpirationEvents = true
                emitter.fire(0f, components.particleLifetimeEvents.expirationEvents, this)
            }

            return alive
        }

        private fun doUpdate(dt: Float): Boolean {
            age += dt

            curveVariables.update()

            fireTimelineEvents()

            if (age >= lifetime) {
                return false
            }

            components.particleLifetimeExpression?.let { config ->
                if (config.expirationExpression.eval(molang) != 0f) {
                    return false
                }
            }

            components.particleMotionParametric?.let { config ->
                position.set(config.relativePosition.eval(molang))
                if (localSpace == null) {
                    position.plusSelf(emitter.position)
                } else if (emitter.locatorOffset != null) {
                    position.plusSelf(emitter.locatorOffset)
                }
                rotationAngle = config.rotation.eval(molang)
                if (config.direction != null) {
                    direction.set(config.direction.eval(molang))
                    velocity.set(vecZero())
                }
            }

            components.particleMotionDynamic?.let { config ->
                val linearAcceleration = config.linearAcceleration.eval(molang).toMutable()
                linearAcceleration.plusScaledSelf(-config.linearDragCoefficient.eval(molang), velocity)
                if (!move(dt, linearAcceleration)) {
                    return false
                }

                var rotAcceleration = config.rotationAcceleration.eval(molang)
                rotAcceleration -= rotationRate * config.rotationDragCoefficient.eval(molang)
                rotAcceleration *= dt
                var deltaRotation = rotationRate
                rotationRate += rotAcceleration
                deltaRotation += rotationRate
                deltaRotation *= 0.5f * dt
                rotationAngle += deltaRotation
            }

            components.particleAppearanceBillboard?.let { config ->
                if (config.direction is FromVelocity) {
                    val lengthSqr = velocity.sqrLength()
                    if (lengthSqr > config.direction.minSpeedThresholdSqr) {
                        direction.set(velocity)
                    }
                }
            }

            return true
        }

        private fun fireTimelineEvents() {
            while (true) {
                val (time, events) = nextTimelineEvent ?: return
                val timeSinceEvent = age - time
                if (timeSinceEvent < 0) return

                emitter.fire(timeSinceEvent, events, this)

                nextTimelineEvent = components.particleLifetimeEvents.timeline.higherEntry(time)
            }
        }

        /**
         * Moves the particle assuming constant [acceleration] over [dt] time.
         * May be invoked recursively in case of collisions. If so, [iteration] will be incremented by one each time.
         * Applies contact friction if [sliding] is `true` (it is for one of the recursive calls).
         */
        private fun move(dt: Float, acceleration: Vec3, iteration: Int = 0, sliding: Boolean = false): Boolean {
            val offset = mutableVec3(velocity)
            offset.plusScaledSelf(0.5f * dt, acceleration)
            offset.timesSelf(dt)

            val config = components.particleMotionCollision
            if (config == null) {
                position.plusSelf(offset)
                velocity.plusScaledSelf(dt, acceleration)
                return true
            }

            val collision = emitter.system.collisionProvider.query(position, config.collisionRadius, offset)
            if (collision == null) {
                position.plusSelf(offset)
                velocity.plusScaledSelf(dt, acceleration)
                if (sliding) {
                    // TODO unclear how this value should be input into this calculation, bedrocks docs give its unit as
                    //      blocks/sec but that's a speed.. idk what to do with that. I would have expected an
                    //      acceleration (blocks/sec/sec) or a per-collision value (but then how long is a collision?
                    //      or when does it turn from zero length to a prolonged contact?).
                    //      For now I'm going to implement it as an acceleration applied for the duration the particle
                    //      is `sliding` (that is, it bounces of the same wall more than once within dt).
                    val speedSqr = velocity.sqrLength()
                    if (speedSqr > 0.0000001f) {
                        val orgSpeed = sqrt(speedSqr)
                        val modifiedSpeed = (orgSpeed - config.collisionDrag * dt).coerceAtLeast(0f)
                        if (modifiedSpeed > 0.0001f) {
                            velocity.timesSelf(modifiedSpeed / orgSpeed)
                        } else {
                            velocity.set(vecZero())
                        }
                    } else {
                        velocity.set(vecZero())
                    }
                }
                return true
            }

            val (maxOffset, surfaceNormal) = collision

            // If this is the third wall we've hit during this time-step, it's about time to give up and settle
            // with whatever safe position we've got, otherwise we could be doing this all day.
            // Same if the particle will die on contact, we'll want the death position correct and no more movement
            // afterwards.
            if (iteration >= 3 || config.expireOnContact) {
                position.plusSelf(maxOffset)
                velocity.plusScaledSelf(dt, acceleration)
                return !config.expireOnContact
            }

            // Estimate time until we hit the surface
            // This is an estimation because it assumes constant velocity (when really we have linear velocity) but
            // collision detection itself also assumes a linear path, so we can't do much better anyway.
            // Note: the sqrt term is equivalent to `len(maxOffset) / len(offset)` but saves one sqrt invocation
            val preDt = sqrt(maxOffset.sqrLength() / offset.sqrLength()).coerceIn(0f, 1f) * dt

            // Compute state right before and right after the collision
            val velocityBeforeHit = velocity.plusScaled(preDt, acceleration)
            val velocityAfterHit = reflect(velocityBeforeHit, surfaceNormal)
            velocityAfterHit.plusScaledSelf(
                (config.coefficientOfRestitution - 1) * velocityAfterHit.dot(surfaceNormal),
                surfaceNormal
            )
            val positionAtHit = position.plus(maxOffset)

            position.set(positionAtHit)
            velocity.set(velocityAfterHit)

            // Continue simulation after the collision
            val postDt = dt - preDt
            val postOffset = mutableVec3(velocityAfterHit)
            postOffset.plusScaledSelf(0.5f * postDt, acceleration)
            postOffset.timesSelf(postDt)
            val positionPostBounce = positionAtHit.plus(postOffset)

            // Fire collision events (if any)
            if (config.events.isNotEmpty()) {
                // Docs do not specify which speed this should be. Simply using the before/after velocity results in an
                // enormous amount of events if the particle is gliding with sufficient speed which doesn't seem
                // helpful.
                // So instead we'll choose the relative speed with respect to the obstacle we hit, so more or less the
                // force of the impact, i.e. the velocity projected onto the obstacle's normal.
                val sqrSpeed = -velocityBeforeHit.dot(surfaceNormal)
                config.events.forEach { eventConfig ->
                    if (sqrSpeed >= eventConfig.minSpeed * eventConfig.minSpeed) {
                        emitter.fire(postDt, eventConfig.event, this)
                    }
                }
            }

            // Check if we're going to make it away from the surface, otherwise we're just going to bounce off of it
            // again and again, potentially infinitely often, and for that we better treat it as a slide than a series
            // of bounces.
            if (positionPostBounce.dot(surfaceNormal) > positionAtHit.dot(surfaceNormal)) {
                // We will make it await from this surface, we could still hit another surface though, enter recursion
                return move(postDt, acceleration, iteration + 1, sliding)
            }

            // We will likely hit the same surface again within the same time step, meaning our bounces at this point
            // are rather tiny, so we'll assume them to be negligible and instead simulate a slide along the surface.
            val accelerationInPlane = acceleration.plusScaled(-acceleration.dot(surfaceNormal), surfaceNormal)
            return move(postDt, accelerationInPlane, iteration + 1, true)
        }

        /**
         * Updates the billboard rendering related fields of this particle for the current frame.
         *
         * @throws UnsupportedOperationException if the particle does not have a billboard component
         */
        fun prepareBillboard(cameraPos: Vec3, cameraRot: Quaternion) {
            val appearance = components.particleAppearanceBillboard ?: throw UnsupportedOperationException()
            val position = globalPosition

            fun computeDirection(): Vec3 {
                val localDirection = when (val config = appearance.direction) {
                    is FromVelocity -> direction
                    is Custom -> config.direction.eval(molang)
                }
                return if (localSpace != null) {
                    localDirection.rotateBy(localSpace.rotation)
                } else {
                    localDirection
                }
            }

            val localSpaceRotation = localSpace?.rotation ?: Quaternion.Identity

            var rot = when (appearance.facingCameraMode) {
                RotateXYZ -> cameraRot.opposite()
                RotateY -> cameraRot.opposite().projectAroundAxis(vecUnitY())
                LookAtXYZ -> Quaternion.fromLookAt(cameraPos.minus(position), vecUnitY())
                LookAtY -> Quaternion.fromLookAt(cameraPos.minus(position).apply { y = 0f }, vecUnitY())
                LookAtDirection -> {
                    val direction = computeDirection()
                    val target = cameraPos.minus(position).apply { plusScaledSelf(-this.dot(direction), direction) }
                    Quaternion.fromLookAt(target, direction.cross(target).normalizeSelf())
                }
                DirectionX -> Quaternion.fromLookAt(computeDirection(), vecUnitY().rotateBy(localSpaceRotation)) *
                        Quaternion.fromAxisAngle(vecUnitY(), -PI.toFloat() / 2)
                // Note: docs say the unrotated x axis should point upwards, but wintersky implements it such that the
                //       z axis / face points upwards, which makes more sense so I'll go with that
                DirectionY -> Quaternion.fromLookAt(computeDirection(), vecUnitY().rotateBy(localSpaceRotation)) *
                        Quaternion.fromAxisAngle(vecUnitX(), -PI.toFloat() / 2) * Quaternion.Y180
                DirectionZ -> Quaternion.fromLookAt(computeDirection(), vecUnitY().rotateBy(localSpaceRotation))
                EmitterTransformXY -> (localSpace?.rotation ?: emitterRotationOnEmit) * Quaternion.Y180
                EmitterTransformXZ -> (localSpace?.rotation ?: emitterRotationOnEmit) * Quaternion.Y180 * Quaternion.fromAxisAngle(vecUnitX(), PI.toFloat() / 2)
                EmitterTransformYZ -> (localSpace?.rotation ?: emitterRotationOnEmit) * Quaternion.fromAxisAngle(vecUnitY(), -PI.toFloat() / 2)
            }

            if (rotationAngle != 0f) {
                rot *= Quaternion.fromAxisAngle(vecUnitZ(), -rotationAngle / 180 * PI.toFloat())
            }

            billboardPosition = position
            billboardRotation = rot
        }

        /**
         * Renders a billboard for this particle.
         * Before this method may be called, [prepareBillboard] must be called each frame.
         *
         * @throws UnsupportedOperationException if the particle does not have a billboard component
         */
        fun renderBillboard(
            matrixStack: UMatrixStack,
            vertexConsumer: UVertexConsumer,
            cameraFacing: Vec3,
            cameraUuid: UUID,
            cameraFirstPerson: Boolean,
            hideParticlesInFirstPerson: Boolean,
        ) {
            // Abide by the particle effect visibility component for the current player.
            if (cameraUuid == emitter.sourceEntity.uuid) {
                if (cameraFirstPerson && hideParticlesInFirstPerson) return
                if (!components.particleVisibility.let { if (cameraFirstPerson) it.firstPerson else it.thirdPerson }) return
            }

            val appearance = components.particleAppearanceBillboard ?: throw UnsupportedOperationException()

            components.particleInitialization?.perRenderExpression?.eval(molang)

            val position = billboardPosition
            val rotation = billboardRotation
            val (sizeX, sizeY) = appearance.size.eval(molang)
            val textureSize = vec2(appearance.uv.textureWidth.toFloat(), appearance.uv.textureHeight.toFloat())
            val color = components.particleAppearanceTinting?.color?.eval(molang)?.let(Color::fromVec) ?: Color.WHITE
            val light = if (components.particleAppearanceLighting != null) {
                emitter.system.lightProvider.query(position)
            } else {
                Light.MAX_VALUE
            }

            var minUV: Vec2
            var maxUV: Vec2

            val flipbook = appearance.uv.flipbook
            if (flipbook != null) {
                val base = flipbook.base.eval(molang)
                val size = flipbook.size.toVec2()
                val step = flipbook.step.toVec2()
                val maxFrame = flipbook.maxFrame.eval(molang).toInt()
                val timePerFrame = if (flipbook.stretchToLifetime) {
                    lifetime / maxFrame
                } else {
                    1 / flipbook.framePerSecond
                }
                val frame = (age / timePerFrame).toInt().let { frame ->
                    if (flipbook.loop) {
                        frame % maxFrame
                    } else {
                        frame.coerceAtMost(maxFrame)
                    }
                }
                minUV = base.plusScaled(frame.toFloat(), step)
                maxUV = minUV.plus(size)
            } else {
                val base = appearance.uv.uv?.eval(molang) ?: vecZero()
                val size = appearance.uv.uvSize?.eval(molang) ?: textureSize
                minUV = base
                maxUV = minUV.plus(size)
            }

            minUV = minUV.div(textureSize)
            maxUV = maxUV.div(textureSize)

            fun emitPoint(x: Float, y: Float, u: Float, v: Float) {
                val pos = mutableVec3(x, y, 0f)
                pos.rotateSelfBy(rotation)
                vertexConsumer
                    .pos(matrixStack, position.x + pos.x.toDouble(), position.y + pos.y.toDouble(), position.z + pos.z.toDouble())
                    .tex(u.toDouble(), v.toDouble())
                    .color(color)
                    .light(light)
                    .endVertex()
            }
            // Instead of actually disabling backface culling, which is somewhat difficult, we'll just flip front and
            // back force when required
            val flip = if (emitter.effect.material.backfaceCulling) {
                false
            } else {
                val billboardNormal = mutableVec3(0f, 0f, -1f).rotateSelfBy(rotation)
                cameraFacing.dot(billboardNormal) > 0
            }
            if (!flip) {
                emitPoint(-sizeX, -sizeY, maxUV.x, maxUV.y)
                emitPoint(-sizeX, +sizeY, maxUV.x, minUV.y)
                emitPoint(+sizeX, +sizeY, minUV.x, minUV.y)
                emitPoint(+sizeX, -sizeY, minUV.x, maxUV.y)
            } else {
                emitPoint(+sizeX, -sizeY, minUV.x, maxUV.y)
                emitPoint(+sizeX, +sizeY, minUV.x, minUV.y)
                emitPoint(-sizeX, +sizeY, maxUV.x, minUV.y)
                emitPoint(-sizeX, -sizeY, maxUV.x, maxUV.y)
            }
        }

        class LocatorFor(val particle: Particle) : Locator {
            override val parent: Locator?
                get() = particle.localSpace
            override val isValid: Boolean
                get() = !particle.firedExpirationEvents
            override val position: Vec3
                get() = particle.globalPosition
            override val rotation: Quaternion
                get() = Quaternion.Identity
            override val velocity: Vec3
                get() = particle.globalVelocity
            override val isVisible: Boolean
                get() = parent?.isVisible ?: true
        }
    }

    interface Locator {
        val parent: Locator?
        val isValid: Boolean
        val position: Vec3
        val rotation: Quaternion
        val velocity: Vec3
        val isVisible: Boolean

        // May be more efficient than calling [position] and [rotation] separately for some implementations
        val positionAndRotation: Pair<Vec3, Quaternion>
            get() = Pair(position, rotation)

        object Zero : Locator {
            override val parent: Locator?
                get() = null
            override val isValid: Boolean
                get() = true
            override val position: Vec3
                get() = vecZero()
            override val rotation: Quaternion
                get() = Quaternion.Identity
            override val velocity: Vec3
                get() = vecZero()
            override val isVisible: Boolean
                get() = true
        }
    }

    interface VertexConsumerProvider {
        fun provide(renderPass: ParticleEffect.RenderPass, block: (UVertexConsumer) -> Unit)
    }
}

/** Reflects [vec] about [norm]. `vec - 2 * (vec * norm) * norm` */
private fun reflect(vec: Vec3, norm: Vec3) =
    vec.plusScaled(-2 * vec.dot(norm), norm)

private fun Pair<Float, Float>.toVec2() = mutableVec2(first, second)

private fun Pair<Molang, Molang>.eval(context: MolangContext) =
    mutableVec2(first.eval(context), second.eval(context))

private class CurveVariables(
    private val context: () -> MolangContext,
    private val curves: Map<String, ParticlesFile.Curve>,
) : Variables {
    private var frame = 0

    private val variables = mutableMapOf<String, Variable?>()

    fun update() {
        frame++
    }

    override fun getOrNull(name: String): Variables.Variable? =
        variables.getOrPut(name) {
            curves["variable.$name"]?.let { Variable(it) }
        }

    override fun getOrPut(name: String, initialValue: Float): Variables.Variable =
        getOrNull(name) ?: throw UnsupportedOperationException("$this does not support unknown variables")

    private inner class Variable(val curve: ParticlesFile.Curve) : Variables.Variable {
        private var cachedFrame = -1
        private var cachedValue: Float = 0f

        override fun get(): Float {
            if (cachedFrame == frame) {
                return cachedValue
            }
            val value = curve.eval(context())
            cachedFrame = frame
            cachedValue = value
            return value
        }
        override fun set(value: Float) = Unit
    }
}

private fun ParticlesFile.Curve.eval(context: MolangContext): Float {
    val range = range.eval(context)
    val input = input.eval(context) / range

    return when (type) {
        ParticlesFile.Curve.Type.Linear -> {
            val position = input * nodes.lastIndex
            val index = position.toInt()
            when {
                index < 0 -> nodes.first().eval(context)
                index >= nodes.lastIndex -> nodes.last().eval(context)
                else -> {
                    val alpha = position - index
                    nodes[index].eval(context).lerp(nodes[index + 1].eval(context), alpha)
                }
            }
        }
        ParticlesFile.Curve.Type.Bezier -> {
            bezier(
                input,
                nodes[0].eval(context),
                nodes[1].eval(context),
                nodes[2].eval(context),
                nodes[3].eval(context),
            )
        }
        ParticlesFile.Curve.Type.CatmullRom -> {
            val position = 1 + input * (nodes.lastIndex - 2)
            val index = position.toInt()
            when {
                index < 1 -> nodes[1].eval(context)
                index >= nodes.lastIndex - 1 -> nodes[nodes.lastIndex - 1].eval(context)
                else -> {
                    val alpha = position - index
                    catmullRom(
                        alpha,
                        nodes[index - 1].eval(context),
                        nodes[index    ].eval(context),
                        nodes[index + 1].eval(context),
                        nodes[index + 2].eval(context),
                    )
                }
            }
        }
        ParticlesFile.Curve.Type.BezierChain -> 0f // TODO requires more complex parsing, and we probably don't need it
    }
}

