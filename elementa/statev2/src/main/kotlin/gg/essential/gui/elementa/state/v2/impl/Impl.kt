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
package gg.essential.gui.elementa.state.v2.impl

import gg.essential.elementa.state.v2.ReferenceHolder
import gg.essential.gui.elementa.state.v2.DelegatingMutableState
import gg.essential.gui.elementa.state.v2.DelegatingState
import gg.essential.gui.elementa.state.v2.MutableState
import gg.essential.gui.elementa.state.v2.Observer
import gg.essential.gui.elementa.state.v2.ReferenceHolderImpl
import gg.essential.gui.elementa.state.v2.State
import gg.essential.gui.elementa.state.v2.mutableStateOf

internal interface Impl {
    fun <T> mutableState(value: T): MutableState<T>
    fun <T> memo(func: Observer.() -> T): State<T>
    fun effect(referenceHolder: ReferenceHolder, func: Observer.() -> Unit): () -> Unit

    fun <T> stateDelegatingTo(state: State<T>): DelegatingState<T> =
        object : DelegatingState<T> {
            private val target = mutableStateOf(state)
            override fun rebind(newState: State<T>) = target.set(newState)
            override fun Observer.get(): T = target()()
        }

    fun <T> mutableStateDelegatingTo(state: MutableState<T>): DelegatingMutableState<T> =
        object : DelegatingMutableState<T> {
            private val target = mutableStateOf(state)
            override fun set(mapper: (T) -> T) = target.getUntracked().set(mapper)
            override fun rebind(newState: MutableState<T>) = target.set(newState)
            override fun Observer.get(): T = target()()
        }

    fun <T> derivedState(
        initialValue: T,
        builder: (owner: ReferenceHolder, derivedState: MutableState<T>) -> Unit,
    ): State<T> =
        object : State<T> {
            val referenceHolder = ReferenceHolderImpl() // keep this alive for at least as long as the returned state
            val derivedState = mutableStateOf(initialValue)
            init {
                builder(referenceHolder, derivedState)
            }

            override fun Observer.get(): T = derivedState()
        }
}
