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
package gg.essential.network.connectionmanager.profile;

import com.google.common.collect.Maps;
import com.sparkuniverse.toolbox.chat.enums.ChannelType;
import com.sparkuniverse.toolbox.chat.model.Channel;
import gg.essential.config.EssentialConfig;
import gg.essential.connectionmanager.common.enums.ActivityType;
import gg.essential.connectionmanager.common.enums.ProfileStatus;
import gg.essential.connectionmanager.common.packet.profile.ClientProfileActivityPacket;
import gg.essential.connectionmanager.common.packet.profile.ServerProfileActivityPacket;
import gg.essential.connectionmanager.common.packet.profile.ServerProfileStatusPacket;
import gg.essential.connectionmanager.common.packet.profile.trustedhosts.ServerProfileTrustedHostsClearPacket;
import gg.essential.connectionmanager.common.packet.profile.trustedhosts.ServerProfileTrustedHostsPopulatePacket;
import gg.essential.connectionmanager.common.packet.profile.trustedhosts.ServerProfileTrustedHostsRemovePacket;
import gg.essential.data.OnboardingData;
import gg.essential.elementa.state.BasicState;
import gg.essential.elementa.state.State;
import gg.essential.gui.EssentialPalette;
import gg.essential.gui.friends.SocialMenu;
import gg.essential.gui.friends.previews.ChannelPreview;
import gg.essential.gui.friends.state.IStatusManager;
import gg.essential.gui.multiplayer.EssentialMultiplayerGui;
import gg.essential.gui.notification.ExtensionsKt;
import gg.essential.gui.notification.Notifications;
import gg.essential.lib.gson.Gson;
import gg.essential.lib.gson.JsonParseException;
import gg.essential.network.connectionmanager.ConnectionCodec;
import gg.essential.network.connectionmanager.ConnectionManager;
import gg.essential.network.connectionmanager.NetworkedManager;
import gg.essential.network.connectionmanager.StateCallbackManager;
import gg.essential.network.connectionmanager.handler.profile.ServerProfileActivityPacketHandler;
import gg.essential.network.connectionmanager.handler.profile.ServerProfileStatusPacketHandler;
import gg.essential.network.connectionmanager.handler.profile.trustedhosts.ServerProfileTrustedHostsClearPacketHandler;
import gg.essential.network.connectionmanager.handler.profile.trustedhosts.ServerProfileTrustedHostsPopulatePacketHandler;
import gg.essential.network.connectionmanager.handler.profile.trustedhosts.ServerProfileTrustedHostsRemovePacketHandler;
import gg.essential.network.connectionmanager.queue.PacketQueue;
import gg.essential.network.connectionmanager.queue.SequentialPacketQueue;
import gg.essential.network.connectionmanager.subscription.SubscriptionManager;
import gg.essential.profiles.model.TrustedHost;
import gg.essential.util.CachedAvatarImage;
import gg.essential.util.GuiUtil;
import gg.essential.util.HttpUtils;
import gg.essential.util.Multithreading;
import gg.essential.util.StringsKt;
import gg.essential.util.UUIDUtil;
import kotlin.Pair;
import kotlin.Unit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static gg.essential.gui.elementa.state.v2.ListKt.clear;

public class ProfileManager extends StateCallbackManager<IStatusManager> implements NetworkedManager, SubscriptionManager.Listener {
    @NotNull
    private final ConnectionManager connectionManager;

    @NotNull
    private final Map<UUID, ProfileStatus> statuses = Maps.newConcurrentMap();

    @NotNull
    private final Map<UUID, Pair<ActivityType, String>> activities = Maps.newConcurrentMap();

    @NotNull
    private final Map<String, TrustedHost> trustedHosts = Maps.newConcurrentMap();

    @NotNull
    private final PacketQueue updateQueue;

    @NotNull
    private final State<Set<String>> trustedHostsState = new BasicState<>(new HashSet<>());

    @NotNull
    private final State<Set<String>> userTrustedHostsState = new BasicState<>(new HashSet<>());

    /**
     * Used to track whether the default trusted hosts have been loaded in the
     * case the user has not accepted the TOS and therefore cannot receive them
     * through the connection manager.
     */
    private boolean loadedDefaultTrustedHosts = false;

    public ProfileManager(@NotNull final ConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
        this.updateQueue = new SequentialPacketQueue.Builder(connectionManager)
            .onTimeoutSkip()
            .create();

        connectionManager.registerPacketHandler(ServerProfileActivityPacket.class, new ServerProfileActivityPacketHandler());
        connectionManager.registerPacketHandler(ServerProfileStatusPacket.class, new ServerProfileStatusPacketHandler());
        connectionManager.registerPacketHandler(ServerProfileTrustedHostsClearPacket.class, new ServerProfileTrustedHostsClearPacketHandler());
        connectionManager.registerPacketHandler(ServerProfileTrustedHostsPopulatePacket.class, new ServerProfileTrustedHostsPopulatePacketHandler());
        connectionManager.registerPacketHandler(ServerProfileTrustedHostsRemovePacket.class, new ServerProfileTrustedHostsRemovePacketHandler());
    }

    @NotNull
    public Map<UUID, Pair<ActivityType, String>> getActivities() {
        return this.activities;
    }

    @NotNull
    public synchronized Map<String, TrustedHost> getTrustedHosts() {
        if ((!OnboardingData.hasAcceptedTos() || !EssentialConfig.INSTANCE.getEssentialEnabled()) && !loadedDefaultTrustedHosts) {
            loadedDefaultTrustedHosts = true;
            try {
                final Gson gson = ConnectionCodec.gson;
                String trustedHostURL = "https://api.essential.gg/v2/trusted_hosts";
                final TrustedHost[] trustedHosts = gson.fromJson(HttpUtils.httpGetToStringBlocking(trustedHostURL), TrustedHost[].class);
                for (TrustedHost trustedHost : trustedHosts) {
                    addTrustedHost(trustedHost);
                }
            } catch (JsonParseException | IOException e) {
                e.printStackTrace();
            }
        }
        return this.trustedHosts;
    }

    @Nullable
    public ProfileStatus getStatus() {
        return this.getStatus(UUIDUtil.getClientUUID());
    }

    @Nullable
    public ActivityType getActivityType() {
        return this.getActivity(UUIDUtil.getClientUUID()).map(Pair::getFirst).orElse(null);
    }

    @NotNull
    public ProfileStatus getStatus(@NotNull final UUID uuid) {
        return this.getStatusIfLoaded(uuid).orElse(ProfileStatus.OFFLINE);
    }

    @NotNull
    public Optional<ProfileStatus> getStatusIfLoaded(@NotNull final UUID uuid) {
        return Optional.ofNullable(this.statuses.get(uuid));
    }

    @NotNull
    public Optional<Pair<ActivityType, String>> getActivity(@NotNull final UUID uuid) {
        return Optional.ofNullable(this.activities.get(uuid));
    }

    @NotNull
    public Optional<TrustedHost> getTrustedHost(@NotNull final String id) {
        return Optional.ofNullable(this.trustedHosts.get(id));
    }

    public void setPlayerStatus(@NotNull final UUID uuid, @NotNull final ProfileStatus status, @Nullable final Long timestamp) {
        final ProfileStatus put = this.statuses.put(uuid, status);

        if (put == ProfileStatus.OFFLINE && status == ProfileStatus.ONLINE && connectionManager.getRelationshipManager().isFriend(uuid) && EssentialConfig.INSTANCE.getFriendConnectionStatus()) {
            UUIDUtil.getName(uuid).thenAcceptAsync(username -> Notifications.INSTANCE.push(
                    "",
                    "",
                    4f,
                    () -> {
                        SocialMenu gui = SocialMenu.getInstance();
                        Channel channel = null;
                        for (Channel value : connectionManager.getChatManager().getChannels().values()) {
                            if (value.getType() == ChannelType.DIRECT_MESSAGE && value.getMembers().contains(uuid)) {
                                channel = value;
                                break;
                            }
                        }
                        if (gui == null) {
                            Long channelId = channel == null ? null : channel.getId();
                            GuiUtil.openScreen(SocialMenu.class, () -> new SocialMenu(channelId));
                        } else if (channel != null) {
                            ChannelPreview cbp = gui.getChatTab().get(channel.getId());
                            if (cbp != null) {
                                gui.openMessageScreen(cbp);
                            }
                        }
                        return Unit.INSTANCE;
                    },
                    () -> Unit.INSTANCE,
                    (notificationBuilder) -> {
                        ExtensionsKt.iconAndMarkdownBody(notificationBuilder,
                            CachedAvatarImage.create(uuid),
                            StringsKt.colored(username, EssentialPalette.TEXT_HIGHLIGHT) + " is online!"
                        );
                        return Unit.INSTANCE;
                    }
            ), Multithreading.getPool());
        }

        for (IStatusManager statusManager : getCallbacks()) {
            statusManager.refreshActivity(uuid);
        }
    }

    public void setPlayerActivity(
        @NotNull final UUID uuid, @Nullable final ActivityType activityType, @Nullable final String metadata
    ) {
        if (activityType == null || metadata == null) {
            this.removePlayerActivity(uuid);
            return;
        }

        this.activities.put(uuid, new Pair<>(activityType, metadata));

        for (IStatusManager statusManager : getCallbacks()) {
            statusManager.refreshActivity(uuid);
        }

        EssentialMultiplayerGui gui = EssentialMultiplayerGui.getInstance();
        if (gui != null) {
            gui.updatePlayerActivity(uuid);
        }
    }

    public void updatePlayerActivity(@Nullable final ActivityType activityType, @Nullable final String metadata) {
        this.setPlayerActivity(UUIDUtil.getClientUUID(), activityType, metadata);
        this.updateQueue.enqueue(new ClientProfileActivityPacket(activityType, metadata));
    }

    public void addTrustedHost(@NotNull final TrustedHost trustedHost) {
        this.trustedHosts.put(trustedHost.getId(), trustedHost);
        updateTrustedHostState();
    }

    private void updateTrustedHostState() {
        trustedHostsState.set(trustedHosts.values().stream().flatMap(trustedHost -> trustedHost.getDomains().stream()).collect(Collectors.toSet()));
        userTrustedHostsState.set(trustedHosts.values().stream().filter(trustedHost -> trustedHost.getProfileId() != null).flatMap(trustedHost -> trustedHost.getDomains().stream()).collect(Collectors.toSet()));
    }

    public void removePlayerActivity(@NotNull final UUID uuid) {
        this.activities.remove(uuid);

        EssentialMultiplayerGui gui = EssentialMultiplayerGui.getInstance();
        if (gui != null) {
            gui.updatePlayerActivity(uuid);
        }

        for (IStatusManager statusManager : getCallbacks()) {
            statusManager.refreshActivity(uuid);
        }
    }

    public void removeTrustedHost(@NotNull final String id) {
        this.trustedHosts.remove(id);
        updateTrustedHostState();
    }

    public void clearTrustedHosts() {
        this.trustedHosts.clear();
        updateTrustedHostState();
    }

    @Override
    public void onConnected() {
        Optional<Pair<ActivityType, String>> clientActivity = this.getActivity(UUIDUtil.getClientUUID());

        resetState();

        clientActivity.ifPresent(activity -> this.updatePlayerActivity(activity.getFirst(), activity.getSecond()));
    }

    @Override
    public void resetState() {
        this.updateQueue.reset();
        this.trustedHosts.clear();
        this.activities.clear();
        this.statuses.clear();
        updateTrustedHostState();
    }


    @Override
    public void onSubscriptionRemoved(@NotNull Set<UUID> uuids) {
        for (UUID uuid : uuids) {
            if (!connectionManager.getRelationshipManager().isFriend(uuid))
                this.removePlayerActivity(uuid);
        }
    }

}
