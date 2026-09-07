package com.hugo.smartexpense.app.modelprofile.data

import com.hugo.smartexpense.extraction.ModelProfile
import com.hugo.smartexpense.extraction.ModelProfileRepository
import com.hugo.smartexpense.extraction.ModelProfileSelectorState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomModelProfileRepository(
    private val dao: ModelProfileDao,
) : ModelProfileRepository {
    override fun observeProfiles(): Flow<List<ModelProfile>> =
        dao.observeProfiles().map { entities -> entities.map(ModelProfileEntity::toDomain) }

    override fun observeSelectorState(): Flow<ModelProfileSelectorState> =
        dao.observeSelectorState().map { it?.toDomain() ?: ModelProfileSelectorState() }

    override suspend fun getProfile(id: String): ModelProfile? = dao.getProfile(id)?.toDomain()

    override suspend fun saveProfile(profile: ModelProfile) = dao.upsertProfile(ModelProfileEntity.fromDomain(profile))

    override suspend fun selectProfile(id: String?) {
        dao.selectExistingProfile(id)
    }

    override suspend fun setRemoteProvidersEnabled(enabled: Boolean) {
        dao.setRemoteProvidersEnabled(enabled)
    }

    override suspend fun deleteProfile(id: String) = dao.deleteProfileAndClearSelection(id)
}
