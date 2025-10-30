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
package gg.essential.gui.friends.tabs

import com.sparkuniverse.toolbox.chat.model.Channel
import gg.essential.Essential
import gg.essential.elementa.UIComponent
import gg.essential.elementa.components.*
import gg.essential.elementa.constraints.CenterConstraint
import gg.essential.elementa.constraints.CopyConstraintFloat
import gg.essential.elementa.constraints.FillConstraint
import gg.essential.elementa.constraints.SiblingConstraint
import gg.essential.elementa.dsl.*
import gg.essential.gui.EssentialPalette
import gg.essential.gui.common.ScrollSpacer
import gg.essential.gui.common.bindChildren
import gg.essential.gui.common.bindParent
import gg.essential.gui.elementa.state.v2.State
import gg.essential.gui.elementa.state.v2.mutableStateOf
import gg.essential.gui.friends.SocialMenu
import gg.essential.gui.friends.Tab
import gg.essential.gui.friends.message.MessageScreen
import gg.essential.gui.friends.message.v2.ReplyableMessageScreen
import gg.essential.gui.friends.previews.ChannelPreview
import gg.essential.gui.util.onItemRemoved
import gg.essential.util.*
import gg.essential.vigilance.gui.VigilancePalette
import java.util.*

class ChatTab(
    gui: SocialMenu,
    selectedTab: State<Tab>
) : TabComponent(Tab.CHAT, gui, selectedTab) {
    private val connectionManager = Essential.getInstance().connectionManager
    private val chatManager = connectionManager.chatManager

    private val horizontalDivider by UIBlock(VigilancePalette.getDarkDivider().darker()).constrain {
        y = SiblingConstraint() boundTo gui.tabsSelector
        width = 100.percent boundTo gui.tabsSelector
        height = gui.dividerWidth.pixels
    } childOf this


    private val channelListScroller by ScrollComponent().constrain {
        y = SiblingConstraint()
        width = 100.percent boundTo horizontalDivider
        height = FillConstraint(useSiblings = false)
    }.also {
        it.emptyText.setColor(VigilancePalette.getDarkText().toConstraint())
    } childOf this scrollGradient 40.pixels


    private val divider by UIBlock(EssentialPalette.COMPONENT_BACKGROUND).constrain {
        height = 100.percent
        width = 100.percent boundTo gui.rightDivider
        x = SiblingConstraint()
    } childOf this

    private val messageScreenArea by UIContainer().constrain {
        x = SiblingConstraint()
        width = FillConstraint(useSiblings = false)
        height = 100.percent
    } childOf this

    private val scrollbarArea by UIContainer().constrain {
        x = CenterConstraint()
        y = CopyConstraintFloat() boundTo channelListScroller
        width = 100.percent
        height = CopyConstraintFloat() boundTo channelListScroller
    } childOf divider

    private val scrollbar by UIBlock(EssentialPalette.SCROLLBAR).constrain {
        width = 100.percent
        x = CenterConstraint()
    } childOf scrollbarArea

    private val titleBarNotch by UIBlock(EssentialPalette.COMPONENT_HIGHLIGHT).constrain {
        height = 100.percent
        width = 100.percent boundTo divider
        x = CenterConstraint() boundTo divider
    }.bindParent(gui.titleBar, active)

    private val previews: Sequence<ChannelPreview>
        get() = channelListScroller.allChildren.asSequence().filterIsInstance<ChannelPreview>()

    override val userLists: List<ScrollComponent> = listOf(channelListScroller)

    private val readSorter: (UIComponent) -> Boolean = { !(it as ChannelPreview).hasUnreadState.get() }

    private val channelSorter = ScrollSpacer.comparator
        .thenBy { !(it as ChannelPreview).hasUnreadState.get() }
        .thenByDescending { (it as ChannelPreview).latestMessageTimestamp.get() }

    var currentMessageView = mutableStateOf<MessageScreen?>(null)

    init {
        channelListScroller.setVerticalScrollBarComponent(scrollbar, hideWhenUseless = true)
    }

    override fun populate() {
        val observableChannelList = messengerStates.getObservableChannelList()

        ScrollSpacer(true).constrain {
            width = 100.percent
            height = 5.pixels
        } childOf channelListScroller

        ScrollSpacer(false).constrain {
            y = SiblingConstraint()
            width = 100.percent
            height = 5.pixels
        } childOf channelListScroller

        observableChannelList.onItemRemoved {
            if (currentMessageView.get()?.preview?.channel == it) {
                openTopChannel(it)
            }
        }

        channelListScroller.bindChildren(
            observableChannelList,
            comparator = channelSorter
        ) {
            ChannelPreview(gui, it)
        }

        messengerStates.onChannelStateChange {
            channelListScroller.sortChildren(channelSorter)
        }
    }

    fun openTopChannel(exclude: Channel? = null) {
        val firstOrNull = channelListScroller.allChildren.filterIsInstance<ChannelPreview>().filter { it.channel != exclude }
            .sortedWith(channelSorter).firstOrNull()
        if (firstOrNull != null) {
            gui.openMessageScreen(firstOrNull)
        }
    }

    operator fun get(uuid: UUID): ChannelPreview? = previews.firstOrNull { it.otherUser == uuid }
    operator fun get(channelId: Long): ChannelPreview? = previews.firstOrNull { it.channel.id == channelId }

    override fun sortUserLists() {
        channelListScroller.sortChildren(channelSorter)
    }

    fun openMessage(preview: ChannelPreview) {

        // Adjust scroll position so that the channel is in view
        if (preview.getTop() < channelListScroller.getTop()) {
            channelListScroller.scrollTo(verticalOffset = channelListScroller.verticalOffset + (channelListScroller.getTop() - preview.getTop()))
        } else if (preview.getBottom() > channelListScroller.getBottom()) {
            channelListScroller.scrollTo(verticalOffset = channelListScroller.verticalOffset + (channelListScroller.getBottom() - preview.getBottom()))
        }

        if (preview == currentMessageView.get()?.preview) {
            return
        }

        currentMessageView.get()?.onClose()

        messageScreenArea.clearChildren()

        val messageScreen = ReplyableMessageScreen(preview)

        currentMessageView.set(messageScreen.constrain {
            width = 100.percent
            height = 100.percent
        } childOf messageScreenArea)

        currentMessageView.get()?.onOpen()
    }
}
