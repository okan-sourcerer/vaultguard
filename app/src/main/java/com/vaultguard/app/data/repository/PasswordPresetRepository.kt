package com.vaultguard.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.vaultguard.app.domain.model.PasswordGeneratorConfig
import com.vaultguard.app.domain.model.PasswordPreset
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PasswordPresetRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val PREFS_NAME = "password_presets"
        private const val KEY_PRESETS = "presets"
    }

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getAll(): List<PasswordPreset> {
        val json = prefs.getString(KEY_PRESETS, null)
        val userPresets = if (json != null) parsePresets(json) else emptyList()
        // Always ensure default is first
        val defaultPreset = userPresets.find { it.id == PasswordPreset.DEFAULT_ID }
            ?: PasswordPreset.DEFAULT
        val others = userPresets.filter { it.id != PasswordPreset.DEFAULT_ID }
        return listOf(defaultPreset.copy(isDefault = true)) + others
    }

    fun save(preset: PasswordPreset): PasswordPreset {
        val all = getAll().toMutableList()
        val id = if (preset.id.isEmpty()) UUID.randomUUID().toString() else preset.id
        val toSave = preset.copy(id = id)

        val index = all.indexOfFirst { it.id == id }
        if (index >= 0) {
            all[index] = toSave.copy(isDefault = all[index].isDefault)
        } else {
            all.add(toSave)
        }
        persist(all)
        return toSave
    }

    fun delete(id: String) {
        if (id == PasswordPreset.DEFAULT_ID) return // never delete default
        val all = getAll().filter { it.id != id }
        persist(all)
    }

    private fun persist(presets: List<PasswordPreset>) {
        val array = JSONArray()
        for (p in presets) {
            array.put(toJson(p))
        }
        prefs.edit().putString(KEY_PRESETS, array.toString()).apply()
    }

    private fun toJson(preset: PasswordPreset): JSONObject {
        val c = preset.config
        return JSONObject().apply {
            put("id", preset.id)
            put("name", preset.name)
            put("isDefault", preset.isDefault)
            put("length", c.length)
            put("includeUppercase", c.includeUppercase)
            put("includeLowercase", c.includeLowercase)
            put("includeDigits", c.includeDigits)
            put("includeSymbols", c.includeSymbols)
            put("excludeAmbiguous", c.excludeAmbiguous)
            put("customSymbols", c.customSymbols)
        }
    }

    private fun parsePresets(json: String): List<PasswordPreset> {
        val array = JSONArray(json)
        return (0 until array.length()).mapNotNull { i ->
            try {
                val obj = array.getJSONObject(i)
                PasswordPreset(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    isDefault = obj.optBoolean("isDefault", false),
                    config = PasswordGeneratorConfig(
                        length = obj.optInt("length", 20),
                        includeUppercase = obj.optBoolean("includeUppercase", true),
                        includeLowercase = obj.optBoolean("includeLowercase", true),
                        includeDigits = obj.optBoolean("includeDigits", true),
                        includeSymbols = obj.optBoolean("includeSymbols", true),
                        excludeAmbiguous = obj.optBoolean("excludeAmbiguous", false),
                        customSymbols = obj.optString("customSymbols", "!@#\$%^&*()-_=+[]{}|;:,.<>?")
                    )
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
