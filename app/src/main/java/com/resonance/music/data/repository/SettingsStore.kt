package com.resonance.music.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** App preferences shared between Settings UI and playback (scrobbling). */
@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val GAPLESS = booleanPreferencesKey("gapless_playback")
        val SCROBBLE = booleanPreferencesKey("scrobble_enabled")
        val OFFLINE_ROOT = stringPreferencesKey("offline_root_uri")
    }

    private val _offlineRoot = MutableStateFlow<String?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            context.settingsDataStore.data.map { it[Keys.OFFLINE_ROOT] }.collect { _offlineRoot.value = it }
        }
    }

    val gaplessPlayback: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.GAPLESS] ?: true }
    val scrobbleEnabled: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.SCROBBLE] ?: true }
    val offlineRoot: StateFlow<String?> = _offlineRoot.asStateFlow()

    suspend fun setGaplessPlayback(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.GAPLESS] = enabled }
    }

    suspend fun setScrobbleEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.SCROBBLE] = enabled }
    }

    suspend fun setOfflineRoot(uri: String?) {
        context.settingsDataStore.edit {
            if (uri == null) it.remove(Keys.OFFLINE_ROOT) else it[Keys.OFFLINE_ROOT] = uri
        }
    }
}
