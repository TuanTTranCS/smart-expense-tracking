package com.hugo.smartexpense.app

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.hugo.smartexpense.extraction.WritableApiKeyStore

class AndroidApiKeyStore(context: Context) : WritableApiKeyStore {
    private val preferences = EncryptedSharedPreferences.create(
        context,
        "remote_provider_credentials",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override fun get(alias: String): String? = preferences.getString(alias, null)

    override fun put(alias: String, value: String) {
        preferences.edit().putString(alias, value).apply()
    }

    override fun remove(alias: String) {
        preferences.edit().remove(alias).apply()
    }
}
