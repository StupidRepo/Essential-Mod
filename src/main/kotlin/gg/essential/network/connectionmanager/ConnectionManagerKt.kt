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
package gg.essential.network.connectionmanager

import gg.essential.Essential
import gg.essential.config.EssentialConfig
import gg.essential.connectionmanager.common.packet.Packet
import gg.essential.connectionmanager.common.util.LoginUtil
import gg.essential.data.OnboardingData
import gg.essential.event.essential.TosAcceptedEvent
import gg.essential.gui.elementa.state.v2.State
import gg.essential.gui.elementa.state.v2.await
import gg.essential.gui.elementa.state.v2.awaitValue
import gg.essential.gui.elementa.state.v2.mutableStateOf
import gg.essential.gui.menu.AccountManager.Companion.refreshCurrentSession
import gg.essential.network.CMConnection
import gg.essential.network.connectionmanager.Connection.KnownCloseReason
import gg.essential.network.connectionmanager.ConnectionManager.Status
import gg.essential.util.Client
import gg.essential.util.ExponentialBackoff
import gg.essential.util.LoginUtil.joinServer
import gg.essential.util.USession
import gg.essential.util.await
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.SelectClause1
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.io.Closeable
import java.time.Instant
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration
import java.time.Duration as JavaDuration

abstract class ConnectionManagerKt : CMConnection {
    @Suppress("LeakingThis")
    private val java: ConnectionManager = this as ConnectionManager

    override val connectionScope = CoroutineScope(SupervisorJob() + Dispatchers.Client)

    private val mutableConnectionStatus = mutableStateOf<Status?>(null)
    val connectionStatus: State<Status?> = mutableConnectionStatus

    val outdated: Boolean
        get() = connectionStatus.get() == Status.OUTDATED

    @JvmField
    protected var connection: Connection? = null

    protected abstract val previouslyConnectedProtocol: Int

    override val usingProtocol: Int
        get() = connection?.usingProtocol ?: previouslyConnectedProtocol

    private val disconnectRequests = Channel<CloseReason>(1, BufferOverflow.DROP_LATEST)

    protected abstract fun completeConnection(connection: Connection)
    protected abstract fun onClose()

    fun close(closeReason: CloseReason) {
        disconnectRequests.trySendBlocking(closeReason)
    }

    private val scope = CoroutineScope(SupervisorJob())
    private var connectLoopJob = scope.launch(Dispatchers.Unconfined, start = CoroutineStart.LAZY) { connectLoop() }

    fun start() {
        connectLoopJob.start()
    }

    suspend fun forceImmediateReconnect() {
        withContext(Dispatchers.Client) { // single threaded to avoid having to deal with races
            if (connectLoopJob.isCancelled) {
                // Someone is already doing it, let's leave it to them and just wait for it
                connectLoopJob.join()
            } else {
                // We are in charge, but once we start this, we must finish it. So we'll launch a job, which will
                // continue to run even if `forceImmediateReconnect` is cancelled, into our internal scope to finish it.
                connectLoopJob.cancel()
                mutableConnectionStatus.set(Status.CANCELLED)
                scope.launch {
                    connectLoopJob.join()
                    connectLoopJob = scope.launch(Dispatchers.Unconfined) { connectLoop() }
                }.join()
            }
        }
    }

    private suspend fun connectLoop() {
        val mojangBackoff = ExponentialBackoff(4.seconds, 1.minutes, 2.0)
        val connectBackoff = ExponentialBackoff(2.seconds, 1.minutes, 2.0)
        val unexpectedCloseBackoff = ExponentialBackoff(10.seconds, 2.minutes, 2.0)

        var exit = false
        while (!exit) {
            updateStatus(null)

            if (!OnboardingData.hasAcceptedTos()) {
                updateStatus(Status.NO_TOS)
                LOGGER.info("Waiting for Terms Of Service to be accepted before attempting connection")
                Essential.EVENT_BUS.await<TosAcceptedEvent>()
                continue
            }

            if (!EssentialConfig.essentialEnabledState.get()) {
                updateStatus(Status.ESSENTIAL_DISABLED)
                LOGGER.info("Waiting for Essential to be re-enabled in its settings before attempting connection")
                withContext(Dispatchers.Client) {
                    EssentialConfig.essentialEnabledState.awaitValue(true)
                }
                continue
            }

            if (java.minecraftHook.session == "undefined") {
                // Session token not yet set, refresh session before connecting
                LOGGER.info("Fetching/refreshing initial MC session token")
                suspendCoroutine { continuation ->
                    refreshCurrentSession(false) { _: USession?, _: String? ->
                        continuation.resume(Unit)
                    }
                }
            }

            // Ignore any disconnect requests we received while we weren't even connected/connecting
            disconnectRequests.tryReceive()

            val session = USession.activeNow()
            val (uuid, userName, token) = session
            LOGGER.info("Authenticating to Mojang as {} ({})", userName, uuid)
            val sharedSecret = LoginUtil.generateSharedSecret()
            val sessionHash = LoginUtil.computeHash(sharedSecret)
            val statusCode = withContext(Dispatchers.IO) {
                joinServer(token, uuid.toString().replace("-", ""), sessionHash)
            }
            if (statusCode != 204) {
                if (statusCode == 429) {
                    // On a delay due to rate-limiting
                    val delay = 5.seconds
                    LOGGER.warn("Got rate-limit by Mojang, waiting {} before re-trying", delay)
                    delay(delay.inWholeMilliseconds)
                    continue
                } else if (statusCode == 403) {
                    LOGGER.info("Session token appears to be invalid, trying to automatically refresh it")
                    val error = withContext(Dispatchers.Client) {
                        suspendCoroutine { continuation ->
                            refreshCurrentSession(true) { _: USession?, error: String? ->
                                continuation.resume(error)
                            }
                        }
                    }
                    if (error != null) {
                        // If we can't refresh the token, only re-try once we get a new one
                        LOGGER.info("Failed to authenticate to Mojang, waiting for new user-supplied session token")
                        updateStatus(Status.MOJANG_UNAUTHORIZED)
                        USession.active.await { it != session }
                        continue
                    }
                } else {
                    LOGGER.warn("Got unexpected reply from Mojang: {}", statusCode)
                }
                val delay = mojangBackoff.increment()
                if (delay.isPositive()) {
                    LOGGER.info("Waiting {} before re-trying", delay)
                    delay(delay.inWholeMilliseconds)
                }
                continue
            }
            mojangBackoff.reset()

            LOGGER.info("Connecting to Essential Connection Manager...")

            var fastUnexpectedClose = false
            val wrapper = ConnectionWrapper()
            try {
                when (val result = wrapper.connect(uuid, userName, sharedSecret)) {
                    ConnectResult.Outdated -> {
                        LOGGER.error("Client version is no longer supported. Will no longer try to connect.")
                        updateStatus(Status.OUTDATED)
                        return
                    }
                    is ConnectResult.Failed -> {
                        val delay = connectBackoff.increment()
                        LOGGER.warn("Failed to connect ({}), re-trying in {}", result.info, delay)
                        updateStatus(Status.GENERAL_FAILURE)
                        delay(delay.inWholeMilliseconds)
                        continue
                    }
                    ConnectResult.Connected -> {}
                }
                connectBackoff.reset()

                LOGGER.info("Connected to Essential Connection Manager.")
                val connectedAt = Instant.now()

                try {
                    withContext(Dispatchers.Client) {
                        completeConnection(wrapper.connection)
                    }
                    updateStatus(Status.SUCCESS)

                    coroutineScope {
                        launch {
                            for (packet in wrapper.packetChannel) {
                                java.packetHandlers.handle(java, packet)
                            }
                        }

                        select {
                            wrapper.onClose { info ->
                                val duration = JavaDuration.between(connectedAt, Instant.now()).toKotlinDuration()
                                fastUnexpectedClose = duration < 2.minutes
                                LOGGER.warn("Connection closed unexpectedly ({}) after {}", info, duration)
                            }
                            async { USession.active.await { it.uuid != session.uuid } }.onAwait { newSession ->
                                val duration = JavaDuration.between(connectedAt, Instant.now()).toKotlinDuration()
                                LOGGER.info("Closing connection after {} to change account from {} to {}",
                                    duration, session.username, newSession.username)
                                wrapper.connection.close(CloseReason.REAUTHENTICATION)
                            }
                            disconnectRequests.onReceive { reason ->
                                val duration = JavaDuration.between(connectedAt, Instant.now()).toKotlinDuration()
                                LOGGER.info("Closing connection after {} due to {}", duration, reason)
                                wrapper.connection.close(reason)
                            }
                        }
                        coroutineContext.cancelChildren()
                    }
                } finally {
                    withContext(NonCancellable + Dispatchers.Client) {
                        onClose()
                    }
                }
            } finally {
                // TODO replace with `use` once continue-from-inline-function is stable
                try {
                    wrapper.close()
                } catch (_: Throwable) {}
            }

            if (fastUnexpectedClose) {
                val delay = unexpectedCloseBackoff.increment()
                if (delay.isPositive()) {
                    LOGGER.info("Waiting {} before re-connecting", delay)
                    delay(delay.inWholeMilliseconds)
                }
            } else {
                unexpectedCloseBackoff.reset()
            }
        }
    }

    private suspend fun updateStatus(status: Status?) {
        withContext(Dispatchers.Client) {
            mutableConnectionStatus.set(status)
        }
    }

    private sealed interface ConnectResult {
        object Connected : ConnectResult
        object Outdated : ConnectResult
        data class Failed(val info: CloseInfo) : ConnectResult
    }

    private class ConnectionWrapper : Connection.Callbacks, Closeable {
        val connection = Connection(this)

        private val openChannel = Channel<Unit>(Channel.CONFLATED)

        val packetChannel = Channel<Packet>()

        private val closeChannel = Channel<CloseInfo>(1, BufferOverflow.DROP_LATEST)
        val onClose: SelectClause1<CloseInfo>
            get() = closeChannel.onReceive

        override fun onOpen() {
            openChannel.trySendBlocking(Unit)
        }

        override fun onPacketAsync(packet: Packet) {
            packetChannel.trySendBlocking(packet)
        }

        override fun onClose(info: CloseInfo) {
            closeChannel.trySendBlocking(info)
        }

        suspend fun connect(uuid: UUID, userName: String, sharedSecret: ByteArray): ConnectResult {
            withContext(Dispatchers.IO) {
                connection.setupAndConnect(uuid, userName, sharedSecret)
            }
            return select {
                openChannel.onReceive { ConnectResult.Connected }
                closeChannel.onReceive { info ->
                    when (info.knownReason) {
                        KnownCloseReason.OUTDATED -> ConnectResult.Outdated
                        null -> ConnectResult.Failed(info)
                        else -> throw AssertionError() // FIXME: Workaround for compiler bug fixed in Kotlin 2.0
                    }
                }
            }
        }

        override fun close() {
            connection.close()
        }
    }

    data class CloseInfo(
        val code: Int,
        val reason: String,
        val remote: Boolean,
        val knownReason: KnownCloseReason?,
    )

    companion object {
        val LOGGER: Logger = LogManager.getLogger("Essential - Connection")
    }
}
