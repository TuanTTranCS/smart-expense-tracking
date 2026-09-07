package com.hugo.smartexpense.app.modelprofile.migration

import android.content.SharedPreferences

class AndroidLegacyModelProfilePreferences(
    private val preferences: SharedPreferences,
) : LegacyModelProfilePreferences {
    override fun contains(key: String) = preferences.contains(key)
    override fun getBoolean(key: String, default: Boolean) = preferences.getBoolean(key, default)
    override fun getString(key: String, default: String) = preferences.getString(key, default) ?: default
    override fun putBoolean(key: String, value: Boolean) { preferences.edit().putBoolean(key, value).apply() }
    override fun putString(key: String, value: String) { preferences.edit().putString(key, value).apply() }
}
