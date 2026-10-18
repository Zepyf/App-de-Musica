package com.example.zepyfyv2

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.zepyfyv2.adapters.QueueAdapter
import com.example.zepyfyv2.adapters.QueueRow
import com.example.zepyfyv2.databinding.BottomSheetQueueBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch

/**
 * Cola de reproducción, abierta desde el reproductor grande.
 *
 * - Tocar una canción salta a ella.
 * - Deslizar a un lado la quita de la cola (salvo la que está sonando).
 * - Arrastrar por el asa la mueve. Con el modo aleatorio activo no se puede
 *   reordenar, porque el orden que se ve no es el orden real de la cola.
 *
 * Se muestra en el orden en que van a sonar las canciones (en aleatorio, el
 * orden aleatorio) y se actualiza sola si la cola cambia mientras está abierta.
 */
class QueueBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetQueueBinding? = null
    private val binding get() = _binding!!

    private var controller: MediaController? = null
    private lateinit var adapter: QueueAdapter
    private var hasScrolledToCurrent = false

    // Estado del arrastre: posiciones en pantalla al empezar y al soltar
    private var dragFrom = RecyclerView.NO_POSITION
    private var dragTo = RecyclerView.NO_POSITION
    private var isDragging = false

    private val playerListener = object : Player.Listener {
        override fun onTimelineChanged(timeline: Timeline, reason: Int) = refresh()
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = refresh()
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = refresh()
    }

    override fun getTheme(): Int = R.style.ZepyfyBottomSheetDialog

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetQueueBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = QueueAdapter(
            onRowClicked = { row ->
                controller?.run {
                    seekToDefaultPosition(row.index)
                    play()
                }
            },
            onStartDrag = { holder -> touchHelper.startDrag(holder) }
        )
        binding.queueRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.queueRecyclerView.adapter = adapter
        touchHelper.attachToRecyclerView(binding.queueRecyclerView)

        // Seguir al controller vigente de MainActivity (se recrea al volver de segundo plano)
        val mainActivity = activity as? MainActivity ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    mainActivity.controllerFlow.collect { bindController(it) }
                } finally {
                    bindController(null)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Abrir desplegada y ocupando ~3/4 de la pantalla
        (dialog as? BottomSheetDialog)?.let { dialog ->
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
                sheet.layoutParams.height = (resources.displayMetrics.heightPixels * 0.75f).toInt()
                sheet.requestLayout()
            }
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            dialog.behavior.skipCollapsed = true
        }
    }

    private fun bindController(newController: MediaController?) {
        if (newController === controller) return
        controller?.removeListener(playerListener)
        controller = newController
        newController?.addListener(playerListener)
        refresh()
    }

    /** Reconstruye la lista a partir de la cola real del reproductor. */
    private fun refresh() {
        if (_binding == null || isDragging) return
        val player = controller ?: return

        val shuffle = player.shuffleModeEnabled
        val timeline = player.currentTimeline
        val current = player.currentMediaItemIndex

        // Recorrer la cola en el orden en que va a sonar
        val rows = mutableListOf<QueueRow>()
        if (!timeline.isEmpty) {
            var index = timeline.getFirstWindowIndex(shuffle)
            while (index != C.INDEX_UNSET && rows.size < player.mediaItemCount) {
                rows += QueueRow(index, player.getMediaItemAt(index), index == current)
                index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, shuffle)
            }
        }

        adapter.dragEnabled = true
        adapter.submit(rows)

        binding.queueEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        binding.queueRecyclerView.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        binding.queueSubtitle.text = buildString {
            append(resources.getQuantityString(R.plurals.queue_count, rows.size, rows.size))
            append(" · ")
            append(getString(R.string.queue_hint))
        }

        // La primera vez, colocar la canción actual cerca de arriba
        if (!hasScrolledToCurrent && rows.isNotEmpty()) {
            hasScrolledToCurrent = true
            val position = rows.indexOfFirst { it.isCurrent }.coerceAtLeast(0)
            (binding.queueRecyclerView.layoutManager as LinearLayoutManager)
                .scrollToPositionWithOffset(position, 0)
        }
    }

    private val touchHelper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
        override fun isLongPressDragEnabled() = false

        override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
            val row = adapter.rowAt(viewHolder.bindingAdapterPosition)
            val drag = if (adapter.dragEnabled) ItemTouchHelper.UP or ItemTouchHelper.DOWN else 0
            // No se puede quitar deslizando la canción que está sonando
            val swipe = if (row == null || row.isCurrent) 0 else ItemTouchHelper.START or ItemTouchHelper.END
            return makeMovementFlags(drag, swipe)
        }

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            if (dragFrom == RecyclerView.NO_POSITION) dragFrom = from
            dragTo = to
            adapter.moveItem(from, to)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            val row = adapter.rowAt(viewHolder.bindingAdapterPosition) ?: return
            controller?.removeMediaItem(row.index) // la lista se actualiza con onTimelineChanged
        }

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                isDragging = true
                val pos = viewHolder?.bindingAdapterPosition ?: RecyclerView.NO_POSITION
                dragFrom = pos
                dragTo = pos
                viewHolder?.itemView?.alpha = 0.7f
            }
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            viewHolder.itemView.alpha = 1f
            if (!isDragging) return
            isDragging = false
            val finalPos = viewHolder.bindingAdapterPosition
            if (finalPos != RecyclerView.NO_POSITION) {
                dragTo = finalPos
            }
            // Sin aleatorio, la posición en pantalla coincide con la posición en la cola
            if (dragFrom != RecyclerView.NO_POSITION && dragTo != RecyclerView.NO_POSITION && dragFrom != dragTo) {
                controller?.moveMediaItem(dragFrom, dragTo) // dispara onTimelineChanged -> refresh
            } else {
                refresh()
            }
        }
    })

    override fun onDestroyView() {
        super.onDestroyView()
        controller?.removeListener(playerListener)
        controller = null
        _binding = null
    }

    companion object {
        const val TAG = "QueueBottomSheet"
    }
}
