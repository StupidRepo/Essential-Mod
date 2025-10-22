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
package gg.essential.network.connectionmanager;

import gg.essential.Essential;
import gg.essential.connectionmanager.common.packet.Packet;
import gg.essential.connectionmanager.common.packet.connection.*;
import gg.essential.connectionmanager.common.packet.partner.ServerPartneredModsPopulatePacket;
import gg.essential.connectionmanager.common.packet.multiplayer.ServerMultiplayerJoinServerPacket;
import gg.essential.connectionmanager.common.packet.relationships.ServerUuidNameMapPacket;
import gg.essential.elementa.state.v2.ReferenceHolder;
import gg.essential.event.client.PostInitializationEvent;
import gg.essential.gui.elementa.state.v2.ReferenceHolderImpl;
import gg.essential.gui.elementa.state.v2.State;
import gg.essential.handlers.GameProfileManager;
import gg.essential.mod.Model;
import gg.essential.mod.Skin;
import gg.essential.network.client.MinecraftHook;
import gg.essential.network.connectionmanager.chat.ChatManager;
import gg.essential.network.connectionmanager.coins.CoinsManager;
import gg.essential.network.connectionmanager.cosmetics.CosmeticsManager;
import gg.essential.network.connectionmanager.cosmetics.EmoteWheelManager;
import gg.essential.network.connectionmanager.cosmetics.OutfitManager;
import gg.essential.network.connectionmanager.cosmetics.PacketHandlers;
import gg.essential.network.connectionmanager.features.DisabledFeaturesManager;
import gg.essential.network.connectionmanager.handler.PacketHandler;
import gg.essential.network.connectionmanager.handler.connection.ClientConnectionDisconnectPacketHandler;
import gg.essential.network.connectionmanager.handler.connection.ServerConnectionReconnectPacketHandler;
import gg.essential.network.connectionmanager.handler.mojang.ServerUuidNameMapPacketHandler;
import gg.essential.network.connectionmanager.handler.multiplayer.ServerMultiplayerJoinServerPacketHandler;
import gg.essential.network.connectionmanager.ice.IceManager;
import gg.essential.network.connectionmanager.ice.IceManagerMcImpl;
import gg.essential.network.connectionmanager.knownservers.KnownServersManager;
import gg.essential.network.connectionmanager.media.ScreenshotManager;
import gg.essential.network.connectionmanager.notices.CosmeticNotices;
import gg.essential.network.connectionmanager.notices.FriendRequestToastNoticeListener;
import gg.essential.network.connectionmanager.notices.GiftedCosmeticNoticeListener;
import gg.essential.network.connectionmanager.notices.NoticeBannerManager;
import gg.essential.network.connectionmanager.notices.NoticesManager;
import gg.essential.network.connectionmanager.notices.PersistentToastNoticeListener;
import gg.essential.network.connectionmanager.notices.SaleNoticeManager;
import gg.essential.network.connectionmanager.notices.SocialMenuNewFriendRequestNoticeManager;
import gg.essential.network.connectionmanager.profile.ProfileManager;
import gg.essential.network.connectionmanager.profile.SuspensionDisconnectHandler;
import gg.essential.network.connectionmanager.relationship.RelationshipManager;
import gg.essential.network.connectionmanager.serverdiscovery.NewServerDiscoveryManager;
import gg.essential.network.connectionmanager.serverdiscovery.ServerDiscoveryManager;
import gg.essential.network.connectionmanager.skins.PlayerSkinLookup;
import gg.essential.network.connectionmanager.skins.SkinsManager;
import gg.essential.network.connectionmanager.social.RulesManager;
import gg.essential.network.connectionmanager.social.SocialManager;
import gg.essential.network.connectionmanager.sps.SPSManager;
import gg.essential.network.connectionmanager.subscription.SubscriptionManager;
import gg.essential.network.connectionmanager.suspension.McSuspensionManager;
import gg.essential.network.connectionmanager.suspension.SuspensionManager;
import gg.essential.network.connectionmanager.telemetry.TelemetryManager;
import gg.essential.sps.McIntegratedServerManager;
import gg.essential.util.ModLoaderUtil;
import gg.essential.util.Multithreading;
import gg.essential.util.USession;
import gg.essential.util.lwjgl3.Lwjgl3Loader;
import kotlin.Unit;
import kotlin.collections.MapsKt;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;
import kotlinx.coroutines.JobKt;
import me.kbrewster.eventbus.Subscribe;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static gg.essential.gui.elementa.state.v2.StateKt.effect;
import static gg.essential.gui.elementa.state.v2.combinators.StateKt.map;
import static kotlinx.coroutines.ExceptionsKt.CancellationException;

public class ConnectionManager extends ConnectionManagerKt {

    @NotNull
    final PacketHandlers packetHandlers = new PacketHandlers();
    @NotNull
    private final ReferenceHolder refHolder = new ReferenceHolderImpl();
    @NotNull
    private final MinecraftHook minecraftHook;
    @NotNull
    private final List<NetworkedManager> managers = new ArrayList<>();
    @NotNull
    private final NoticesManager noticesManager;
    @NotNull
    private final DisabledFeaturesManager disabledFeaturesManager;
    @NotNull
    private final SubscriptionManager subscriptionManager;
    @NotNull
    private final RelationshipManager relationshipManager;
    @NotNull
    private final CosmeticsManager cosmeticsManager;
    @NotNull
    private final ChatManager chatManager;
    @NotNull
    private final ProfileManager profileManager;
    @NotNull
    private final SPSManager spsManager;
    @NotNull
    private final ServerDiscoveryManager serverDiscoveryManager;
    @NotNull
    private final SocialManager socialManager;
    @NotNull
    private final IceManager iceManager;
    @NotNull
    private final ScreenshotManager screenshotManager;
    @NotNull
    private final TelemetryManager telemetryManager;
    //@NotNull
    private /*final*/ CoinsManager coinsManager;
    //@NotNull
    private /*final*/ SkinsManager skinsManager;
    @NotNull
    private final OutfitManager outfitManager;
    @NotNull
    private final EmoteWheelManager emoteWheelManager;
    // @NotNull
    private /* final */ NewServerDiscoveryManager newServerDiscoveryManager;
    // @NotNull
    private /* final */ KnownServersManager knownServersManager;
    @NotNull
    private final CosmeticNotices cosmeticNotices;
    @NotNull
    private final SaleNoticeManager saleNoticeManager;
    @NotNull
    private final SocialMenuNewFriendRequestNoticeManager socialMenuNewFriendRequestNoticeManager;
    @NotNull
    private final NoticeBannerManager noticeBannerManager;
    private /* final */ SuspensionManager suspensionManager;
    private /* final */ RulesManager rulesManager;

    private boolean modsSent = false;
    private int previouslyConnectedProtocol = 1;

    public enum Status {
        NO_TOS,
        ESSENTIAL_DISABLED,
        OUTDATED,
        USER_SUSPENDED,
        CANCELLED,
        ALREADY_CONNECTED,
        NO_RESPONSE,
        INVALID_RESPONSE,
        MOJANG_UNAUTHORIZED,
        GENERAL_FAILURE,
        SUCCESS,
    }

    public ConnectionManager(
        @NotNull final MinecraftHook minecraftHook,
        File baseDir,
        Lwjgl3Loader lwjgl3,
        State<@Nullable McIntegratedServerManager> integratedServerManager
    ) {
        this.minecraftHook = minecraftHook;
        this.subscriptionManager = new SubscriptionManager(this);
        this.managers.add(this.subscriptionManager);

        // Connections
        this.registerPacketHandler(ClientConnectionDisconnectPacket.class, new ClientConnectionDisconnectPacketHandler());
        this.registerPacketHandler(ServerConnectionReconnectPacket.class, new ServerConnectionReconnectPacketHandler());

        // Multiplayer
        this.registerPacketHandler(ServerMultiplayerJoinServerPacket.class, new ServerMultiplayerJoinServerPacketHandler());

        // Mojang API
        this.registerPacketHandler(ServerUuidNameMapPacket.class, new ServerUuidNameMapPacketHandler());

        // Mod Partners
        this.registerPacketHandler(ServerPartneredModsPopulatePacket.class, packet -> {
            ModLoaderUtil.populatePartnerMods(packet.getMods());
            return Unit.INSTANCE;
        });

        // Notices
        this.managers.add((this.noticesManager = new NoticesManager(this)));

        noticesManager.register(noticeBannerManager = new NoticeBannerManager(noticesManager));
        noticesManager.register(new PersistentToastNoticeListener(noticesManager));

        // Disabled Features
        this.managers.add(this.disabledFeaturesManager = new DisabledFeaturesManager(this));

        // Cosmetics
        this.cosmeticsManager = new CosmeticsManager(this, baseDir);
        this.managers.add(this.cosmeticsManager);
        this.managers.add(this.cosmeticsManager.getInfraEquippedOutfitsManager());
        noticesManager.register(cosmeticNotices = new CosmeticNotices(noticesManager, cosmeticsManager.getCosmeticsData()));
        noticesManager.register(saleNoticeManager = new SaleNoticeManager());
        noticesManager.register(new GiftedCosmeticNoticeListener(noticesManager, cosmeticsManager.getCosmeticsData()));

        // Relationships
        this.relationshipManager = new RelationshipManager(this);
        this.managers.add(this.relationshipManager);

        // Chat
        this.chatManager = new ChatManager(this);
        this.managers.add(this.chatManager);

        // Profile
        this.profileManager = new ProfileManager(this);
        this.managers.add(this.profileManager);

        // SPS
        this.spsManager = new SPSManager(this);
        this.managers.add(this.spsManager);

        // Server Discovery
        this.serverDiscoveryManager = new ServerDiscoveryManager(this);
        this.managers.add(this.serverDiscoveryManager);

        // Social Manager
        this.managers.add(this.socialManager = new SocialManager(this));
        noticesManager.register(new FriendRequestToastNoticeListener(this, noticesManager));
        noticesManager.register(socialMenuNewFriendRequestNoticeManager = new SocialMenuNewFriendRequestNoticeManager(noticesManager));

        // Ice
        this.iceManager = new IceManagerMcImpl(
            this,
            baseDir.toPath(),
            integratedServerManager,
            uuid -> {
                if (this.spsManager.getInvitedUsers().contains(uuid)) {
                    return true;
                }
                McIntegratedServerManager server = integratedServerManager.getUntracked();
                if (server != null) {
                    Set<UUID> whitelist = server.getWhitelist().getUntracked();
                    return whitelist != null && whitelist.contains(uuid);
                }
                return false;
            }
        );

        //Screenshots
        this.managers.add(this.screenshotManager = new ScreenshotManager(this, baseDir, lwjgl3));

        // Telemetry
        this.managers.add(this.telemetryManager = new TelemetryManager(this));

        // Coins
        this.managers.add(this.coinsManager = new CoinsManager(this));

        // Skins
        this.managers.add(this.skinsManager = new SkinsManager(this));

        // Outfits
        this.outfitManager = new OutfitManager(
            this,
            this.cosmeticsManager.getCosmeticsData(),
            this.cosmeticsManager.getUnlockedCosmetics(),
            this.cosmeticsManager.getInfraEquippedOutfitsManager(),
            map(this.skinsManager.getSkins(), map -> MapsKt.mapValues(map, it -> it.getValue().getSkin()))
        );
        this.managers.add(this.outfitManager);
        effect(refHolder, observer -> {
            Skin skin = this.outfitManager.getEquippedSkin().get(observer);
            if (skin == null) return Unit.INSTANCE;
            Model model = skin.getModel();
            String hash = skin.getHash();
            String url = String.format(Locale.ROOT, GameProfileManager.SKIN_URL, hash);
            USession session = USession.Companion.activeNow();
            Essential.getInstance().getSkinManager().changeSkin(session.getToken(), model, url);
            PlayerSkinLookup.INSTANCE.put(session.getUuid(), skin);
            return Unit.INSTANCE;
        });

        // Emote Wheels
        this.managers.add(this.emoteWheelManager = new EmoteWheelManager(this, this.cosmeticsManager.getUnlockedCosmetics()));

        this.managers.add(this.knownServersManager = new KnownServersManager(this));
        this.managers.add(this.newServerDiscoveryManager = new NewServerDiscoveryManager(
            this,
            this.knownServersManager,
            this.telemetryManager::enqueue
        ));

        this.managers.add(this.suspensionManager = new McSuspensionManager(this));
        this.managers.add(this.rulesManager = new RulesManager(this));
        SuspensionDisconnectHandler.INSTANCE.setupEffects(this);

        PlayerSkinLookup.INSTANCE.register(this);
    }

    @NotNull
    public MinecraftHook getMinecraftHook() {
        return this.minecraftHook;
    }

    @NotNull
    public NoticesManager getNoticesManager() {
        return noticesManager;
    }

    @NotNull
    public DisabledFeaturesManager getDisabledFeaturesManager() {
        return disabledFeaturesManager;
    }

    @NotNull
    public SubscriptionManager getSubscriptionManager() {
        return this.subscriptionManager;
    }

    @NotNull
    public RelationshipManager getRelationshipManager() {
        return this.relationshipManager;
    }

    @NotNull
    public CosmeticsManager getCosmeticsManager() {
        return this.cosmeticsManager;
    }

    @NotNull
    public ChatManager getChatManager() {
        return this.chatManager;
    }

    @NotNull
    public ProfileManager getProfileManager() {
        return this.profileManager;
    }

    @NotNull
    public SPSManager getSpsManager() {
        return this.spsManager;
    }

    @NotNull
    public SocialManager getSocialManager() {
        return this.socialManager;
    }

    @NotNull
    public ScreenshotManager getScreenshotManager() {
        return screenshotManager;
    }

    @NotNull
    public IceManager getIceManager() {
        return this.iceManager;
    }

    @NotNull
    public TelemetryManager getTelemetryManager() {
        return this.telemetryManager;
    }

    @NotNull
    public CoinsManager getCoinsManager() {
        return coinsManager;
    }

    @NotNull
    public SkinsManager getSkinsManager() {
        return skinsManager;
    }

    @NotNull
    public OutfitManager getOutfitManager() {
        return this.outfitManager;
    }

    @NotNull
    public EmoteWheelManager getEmoteWheelManager() {
        return this.emoteWheelManager;
    }

    @NotNull
    public NewServerDiscoveryManager getNewServerDiscoveryManager() {
        return this.newServerDiscoveryManager;
    }

    @NotNull
    public KnownServersManager getKnownServersManager() {
        return this.knownServersManager;
    }

    public @NotNull CosmeticNotices getCosmeticNotices() {
        return this.cosmeticNotices;
    }

    public @NotNull SaleNoticeManager getSaleNoticeManager() {
        return saleNoticeManager;
    }

    public @NotNull SocialMenuNewFriendRequestNoticeManager getSocialMenuNewFriendRequestNoticeManager() {
        return socialMenuNewFriendRequestNoticeManager;
    }

    public @NotNull NoticeBannerManager getNoticeBannerManager() {
        return noticeBannerManager;
    }

    public @NotNull SuspensionManager getSuspensionManager() {
        return this.suspensionManager;
    }

    public @NotNull RulesManager getRulesManager() {
        return this.rulesManager;
    }

    @Override
    public boolean isOpen() {
        Connection connection = this.connection;
        return connection != null && connection.isOpen();
    }

    public boolean isAuthenticated() {
        return this.connection != null;
    }

    @Override
    public void registerOnConnected(@NotNull Function0<Unit> onConnected) {
        this.managers.add(new NetworkedManager() {
            @Override
            public void onConnected() {
                onConnected.invoke();
            }
        });
    }

    public <T extends Packet> void registerPacketHandler(Class<T> cls, PacketHandler<T> handler) {
        this.packetHandlers.register(cls, handler);
    }

    @Override
    public <T extends Packet> void registerPacketHandler(@NotNull Class<T> cls, @NotNull Function1<? super T, Unit> handler) {
        registerPacketHandler(cls, new PacketHandler<T>() {
            @Override
            protected void onHandle(@NotNull ConnectionManager connectionManager, @NotNull T packet) {
                handler.invoke(packet);
            }
        });
    }

    protected void completeConnection(Connection connection) {
        this.connection = connection;

        for (NetworkedManager manager : this.managers) {
            manager.onConnected();
        }

        // Do not want to block the current thread for this (reads mod files to create checksums)
        if (ModLoaderUtil.areModsLoaded.getUntracked() && !modsSent) {
            Multithreading.runAsync(() -> {
                send(ModLoaderUtil.createModsAnnouncePacket());
            });
            modsSent = true;
        }
    }

    protected void onClose() {
        if (this.connection != null) {
            this.previouslyConnectedProtocol = this.connection.getUsingProtocol();
            this.connection = null;
        }
        this.modsSent = false;

        JobKt.cancelChildren(getConnectionScope().getCoroutineContext(), CancellationException("Connection closed.", null));

        for (NetworkedManager manager : this.managers) {
            manager.onDisconnect();
        }
    }

    /**
     * Send a packet to the Connection Manager not caring if it was truly received or a response for said packet.
     *
     * @param packet to send to the connection manager
     * @deprecated Use {@link #call(Packet)} builder instead.
     */
    @Deprecated
    public void send(@NotNull final Packet packet) {
        this.send(packet, null);
    }

    /**
     * Send a packet to the Connection Manager with a callback for when we get a response for the packet we sent
     * with a default time out of 10 seconds.
     *
     * @param packet           to send to the connection manager
     * @param responseCallback callback to use when we receive a response
     * @deprecated Use {@link #call(Packet)} builder instead.
     */
    @Deprecated
    @Override
    public void send(
        @NotNull final Packet packet, @Nullable final Consumer<Optional<Packet>> responseCallback
    ) {
        this.send(packet, responseCallback, TimeUnit.SECONDS, 10L);
    }

    //

    /**
     * Send a packet to the Connection Manager with a callback for when we get a response for the packet we sent.
     * We also support timeouts for the packet we are sending so we can handle timeouts.
     *
     * @param packet           to send to the connection manager
     * @param responseCallback callback to use when we receive a response
     * @param timeoutUnit      time unit to use for the timeout
     * @param timeoutValue     value to use for the timeout time unit
     * @deprecated Use {@link #call(Packet)} builder instead.
     */
    @Deprecated
    @Override
    public void send(
        @NotNull final Packet packet, @Nullable final Consumer<Optional<Packet>> responseCallback,
        @Nullable final TimeUnit timeoutUnit, @Nullable final Long timeoutValue
    ) {
        Connection connection = this.connection;

        if (connection == null || !connection.isOpen()) {
            if (responseCallback != null) {
                responseCallback.accept(Optional.empty());
            }

            return;
        }

        final boolean wantsResponseHandling = (responseCallback != null && timeoutUnit != null && timeoutValue != null);
        UUID packetId = packet.getPacketUniqueId();
        packetId = (wantsResponseHandling && packetId == null ? UUID.randomUUID() : packetId);
        packet.setUniqueId(packetId);

        if (wantsResponseHandling) {
            this.packetHandlers.register(packetId, timeoutUnit, timeoutValue, responseCallback);
        }

        connection.send(packet);
    }

    @Subscribe
    public void onPostInit(PostInitializationEvent event) {
        ModLoaderUtil.setModsLoaded();
        if (!modsSent && isAuthenticated()) {
            Multithreading.runAsync(() -> {
                send(ModLoaderUtil.createModsAnnouncePacket());
            });
            modsSent = true;
        }
    }

    public ServerDiscoveryManager getServerDiscoveryManager() {
        return this.serverDiscoveryManager;
    }

    public void onTosRevokedOrEssentialDisabled() {
        if (this.isOpen()) {
            this.close(CloseReason.USER_TOS_REVOKED);
        }
        for (NetworkedManager manager : this.managers) {
            manager.resetState();
        }
    }

    @Override
    protected int getPreviouslyConnectedProtocol() {
        return previouslyConnectedProtocol;
    }
}
