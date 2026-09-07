package com.hugo.smartexpense.app.modelprofile.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.hugo.smartexpense.extraction.ModelProfile
import com.hugo.smartexpense.extraction.ModelProfileSelectorState
import com.hugo.smartexpense.extraction.RemoteInputMode
import com.hugo.smartexpense.extraction.RemoteStructuredOutputFormat

@Entity(tableName = "model_profiles")
data class ModelProfileEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "base_url") val baseUrl: String,
    @ColumnInfo(name = "model_id") val modelId: String,
    @ColumnInfo(name = "input_mode") val inputMode: RemoteInputMode,
    @ColumnInfo(name = "structured_output_format") val structuredOutputFormat: RemoteStructuredOutputFormat,
    @ColumnInfo(name = "credential_alias") val credentialAlias: String,
    @ColumnInfo(name = "created_at_epoch_millis") val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis") val updatedAtEpochMillis: Long,
) {
    fun toDomain(): ModelProfile = ModelProfile(
        id, displayName, baseUrl, modelId, inputMode,
        structuredOutputFormat, credentialAlias,
        createdAtEpochMillis, updatedAtEpochMillis,
    )

    companion object {
        fun fromDomain(value: ModelProfile) = ModelProfileEntity(
            value.id, value.displayName, value.baseUrl, value.modelId, value.inputMode,
            value.structuredOutputFormat, value.credentialAlias,
            value.createdAtEpochMillis, value.updatedAtEpochMillis,
        )
    }
}

@Entity(tableName = "model_selector_state")
data class ModelSelectorStateEntity(
    @PrimaryKey val singletonId: Int = SINGLETON_ID,
    @ColumnInfo(name = "selected_remote_profile_id") val selectedRemoteProfileId: String? = null,
    @ColumnInfo(name = "remote_providers_enabled") val remoteProvidersEnabled: Boolean = false,
) {
    fun toDomain() = ModelProfileSelectorState(selectedRemoteProfileId, remoteProvidersEnabled)

    companion object { const val SINGLETON_ID = 1 }
}
