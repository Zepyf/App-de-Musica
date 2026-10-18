@file:OptIn(UnstableApi::class)

package com.example.zepyfyv2.media

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ShuffleOrder
import kotlin.random.Random

/**
 * Orden de reproducción aleatorio inteligente que respeta la intención del usuario:
 *
 * 1. "Reproducir a continuación" (playNext): las canciones insertadas justo después de la
 *    canción actual se colocan INMEDIATAMENTE después en la secuencia aleatoria.
 * 2. "Añadir a la cola": las canciones insertadas al final se colocan al final de la
 *    secuencia aleatoria.
 * 3. Activación de aleatorio: la canción que está sonando se conserva como la primera de
 *    la secuencia aleatoria para no interrumpir la reproducción.
 */
class SmartShuffleOrder private constructor(
    private val shuffled: IntArray,
    private val getCurrentPlayingIndex: () -> Int,
    private val random: Random = Random.Default
) : ShuffleOrder {

    constructor(
        length: Int,
        getCurrentPlayingIndex: () -> Int,
        random: Random = Random.Default
    ) : this(createShuffledIndices(length, random), getCurrentPlayingIndex, random)

    constructor(
        length: Int,
        firstIndex: Int,
        getCurrentPlayingIndex: () -> Int,
        random: Random = Random.Default
    ) : this(createShuffledIndicesWithFirst(length, firstIndex, random), getCurrentPlayingIndex, random)

    override fun getLength(): Int = shuffled.size

    override fun getFirstIndex(): Int = if (shuffled.isNotEmpty()) shuffled[0] else C.INDEX_UNSET

    override fun getLastIndex(): Int = if (shuffled.isNotEmpty()) shuffled.last() else C.INDEX_UNSET

    override fun getNextIndex(index: Int): Int {
        val pos = shuffled.indexOf(index)
        if (pos == -1 || pos == shuffled.lastIndex) return C.INDEX_UNSET
        return shuffled[pos + 1]
    }

    override fun getPreviousIndex(index: Int): Int {
        val pos = shuffled.indexOf(index)
        if (pos <= 0) return C.INDEX_UNSET
        return shuffled[pos - 1]
    }

    override fun cloneAndInsert(insertionIndex: Int, insertionCount: Int): ShuffleOrder {
        if (insertionCount <= 0) return this
        val currentPlaying = getCurrentPlayingIndex()

        // Desplazar índices existentes >= insertionIndex
        val shifted = shuffled.map { if (it >= insertionIndex) it + insertionCount else it }.toMutableList()
        val newIndices = (insertionIndex until (insertionIndex + insertionCount)).toList()

        if (shifted.isEmpty()) {
            return SmartShuffleOrder(newIndices.toIntArray(), getCurrentPlayingIndex, random)
        }

        val targetInsertPos = when {
            // "Reproducir a continuación": insertado justo después de la canción actual
            currentPlaying != C.INDEX_UNSET && insertionIndex == currentPlaying + 1 -> {
                val currentPos = shifted.indexOf(currentPlaying)
                if (currentPos != -1) currentPos + 1 else shifted.size
            }
            // Insertado al principio del todo
            insertionIndex == 0 -> 0
            // Insertado al final del todo ("añadir a la cola")
            insertionIndex >= shifted.size -> shifted.size
            // Insertado tras un elemento específico
            else -> {
                val prevPos = shifted.indexOf(insertionIndex - 1)
                if (prevPos != -1) prevPos + 1 else shifted.size
            }
        }

        shifted.addAll(targetInsertPos.coerceIn(0, shifted.size), newIndices)
        return SmartShuffleOrder(shifted.toIntArray(), getCurrentPlayingIndex, random)
    }

    override fun cloneAndRemove(indexFrom: Int, indexTo: Int): ShuffleOrder {
        if (indexFrom >= indexTo) return this
        val removeCount = indexTo - indexFrom
        val remaining = shuffled.filter { it < indexFrom || it >= indexTo }
        val updated = remaining.map { if (it >= indexTo) it - removeCount else it }.toIntArray()
        return SmartShuffleOrder(updated, getCurrentPlayingIndex, random)
    }

    override fun cloneAndClear(): ShuffleOrder {
        return SmartShuffleOrder(intArrayOf(), getCurrentPlayingIndex, random)
    }

    companion object {
        private fun createShuffledIndices(length: Int, random: Random): IntArray {
            val list = (0 until length).toMutableList()
            list.shuffle(random)
            return list.toIntArray()
        }

        private fun createShuffledIndicesWithFirst(length: Int, firstIndex: Int, random: Random): IntArray {
            if (length <= 0) return intArrayOf()
            val list = (0 until length).filter { it != firstIndex }.toMutableList()
            list.shuffle(random)
            if (firstIndex in 0 until length) {
                list.add(0, firstIndex)
            }
            return list.toIntArray()
        }
    }
}
