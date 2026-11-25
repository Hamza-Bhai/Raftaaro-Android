package io.github.hamzabhai.raftaaro.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import io.github.hamzabhai.raftaaro.core.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap

private object K {
    val videosDir = stringPreferencesKey("videosDir")
    val audioDir = stringPreferencesKey("audioDir")
    val filesDir = stringPreferencesKey("filesDir")
    val partsPerFile = intPreferencesKey("partsPerFile")
    val maxConcurrent = intPreferencesKey("maxConcurrent")
    val useSignIns = booleanPreferencesKey("useSignIns")
    val clipboardPrompt = booleanPreferencesKey("clipboardPrompt")
    val lastYtDlpUpdate = longPreferencesKey("lastYtDlpUpdate")
    val batteryTipShown = booleanPreferencesKey("batteryTipShown")
}

/** DataStore allows one instance per file per process. */
private val stores = ConcurrentHashMap<String, DataStore<Preferences>>()

class SettingsStore(context: Context, name: String = "settings") {
    private val store = stores.getOrPut(name) {
        PreferenceDataStoreFactory.create { context.applicationContext.preferencesDataStoreFile(name) }
    }

    val settings: Flow<Settings> = store.data.map { settingsOf(it) }

    suspend fun current(): Settings = settings.first()

    suspend fun update(change: (Settings) -> Settings) {
        store.edit { p ->
            val s = change(settingsOf(p))
            fun <T> put(key: Preferences.Key<T>, v: T?) { if (v == null) p.remove(key) else p[key] = v }
            put(K.videosDir, s.videosDir); put(K.audioDir, s.audioDir); put(K.filesDir, s.filesDir)
            p[K.partsPerFile] = s.partsPerFile.coerceIn(1, 16)
            p[K.maxConcurrent] = s.maxConcurrent.coerceIn(1, 5)
            p[K.useSignIns] = s.useSignIns
            p[K.clipboardPrompt] = s.clipboardPrompt
            put(K.lastYtDlpUpdate, s.lastYtDlpUpdate)
            p[K.batteryTipShown] = s.batteryTipShown
        }
    }

    private fun settingsOf(p: Preferences): Settings {
        val d = Settings()
        return Settings(
            p[K.videosDir], p[K.audioDir], p[K.filesDir], p[K.partsPerFile] ?: d.partsPerFile,
            p[K.maxConcurrent] ?: d.maxConcurrent, p[K.useSignIns] ?: d.useSignIns,
            p[K.clipboardPrompt] ?: d.clipboardPrompt, p[K.lastYtDlpUpdate], p[K.batteryTipShown] ?: d.batteryTipShown
        )
    }
}
