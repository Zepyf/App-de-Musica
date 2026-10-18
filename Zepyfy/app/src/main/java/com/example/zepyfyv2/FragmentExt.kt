package com.example.zepyfyv2

import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Observa [flow] solo mientras la vista del fragment está visible (STARTED).
 * Al pasar a segundo plano deja de observar, lo que permite que MusicCatalog
 * cierre sus listeners si nadie más los usa. Ignora los `null` (= cargando).
 *
 * Llamar desde onViewCreated.
 */
fun <T : Any> Fragment.collectWhileStarted(flow: Flow<T?>, action: suspend (T) -> Unit) {
    viewLifecycleOwner.lifecycleScope.launch {
        viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            flow.filterNotNull().collect { action(it) }
        }
    }
}
