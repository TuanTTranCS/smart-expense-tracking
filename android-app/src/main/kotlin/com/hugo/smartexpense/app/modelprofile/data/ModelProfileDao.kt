package com.hugo.smartexpense.app.modelprofile.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ModelProfileDao {
    @Query("SELECT * FROM model_profiles ORDER BY display_name COLLATE NOCASE ASC, id ASC")
    abstract fun observeProfiles(): Flow<List<ModelProfileEntity>>

    @Query("SELECT * FROM model_selector_state WHERE singletonId = 1")
    abstract fun observeSelectorState(): Flow<ModelSelectorStateEntity?>

    @Query("SELECT * FROM model_profiles WHERE id = :id")
    abstract suspend fun getProfile(id: String): ModelProfileEntity?

    @Query("SELECT * FROM model_selector_state WHERE singletonId = 1")
    abstract suspend fun getSelectorState(): ModelSelectorStateEntity?

    @Upsert
    abstract suspend fun upsertProfile(profile: ModelProfileEntity)

    @Upsert
    abstract suspend fun upsertSelectorState(state: ModelSelectorStateEntity)

    @Query("DELETE FROM model_profiles WHERE id = :id")
    protected abstract suspend fun deleteProfileRow(id: String)

    @Transaction
    open suspend fun deleteProfileAndClearSelection(id: String) {
        deleteProfileRow(id)
        val selector = getSelectorState() ?: ModelSelectorStateEntity()
        if (selector.selectedRemoteProfileId == id) {
            upsertSelectorState(selector.copy(selectedRemoteProfileId = null))
        }
    }

    @Transaction
    open suspend fun selectExistingProfile(id: String?) {
        val safeId = id?.takeIf { getProfile(it) != null }
        val selector = getSelectorState() ?: ModelSelectorStateEntity()
        upsertSelectorState(selector.copy(selectedRemoteProfileId = safeId))
    }

    @Transaction
    open suspend fun setRemoteProvidersEnabled(enabled: Boolean) {
        val selector = getSelectorState() ?: ModelSelectorStateEntity()
        upsertSelectorState(selector.copy(remoteProvidersEnabled = enabled))
    }
}
