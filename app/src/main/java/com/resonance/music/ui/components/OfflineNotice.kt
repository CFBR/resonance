package com.resonance.music.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.resonance.music.data.repository.ServerHealth
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class OfflineStateViewModel @Inject constructor(health: ServerHealth) : ViewModel() {
    /** True while reads come from the saved copy. */
    val offline: StateFlow<Boolean> = flow {
        while (true) {
            emit(health.isOffline())
            delay(POLL_MS)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), health.isOffline())

    private companion object {
        /** A screen that stays open through a reconnect must notice within a beat. */
        const val POLL_MS = 3_000L
    }
}

/**
 * Says plainly that the screen is showing the saved copy, because the two look
 * identical otherwise: the same albums, the same artwork, and a play count that
 * silently stopped moving. Hidden when the server answers, so it carries no
 * noise in normal use.
 */
@Composable
fun OfflineNotice(
    modifier: Modifier = Modifier,
    viewModel: OfflineStateViewModel = hiltViewModel()
) {
    val offline by viewModel.offline.collectAsStateWithLifecycle()
    AnimatedVisibility(visible = offline, modifier = modifier) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .fillMaxWidth()
        ) {
            Text(
                text = "Showing the saved copy. Changes need the server.",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}
