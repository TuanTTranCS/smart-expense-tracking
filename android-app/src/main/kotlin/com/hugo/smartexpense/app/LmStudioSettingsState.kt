package com.hugo.smartexpense.app

import com.hugo.smartexpense.extraction.LmStudioTestProfile
import com.hugo.smartexpense.extraction.RemoteInputMode

data class LmStudioSettingsState(
    val remoteEnabled: Boolean = false,
    val baseUrl: String = LmStudioTestProfile.baseUrl,
    val modelId: String = LmStudioTestProfile.modelId,
    val inputMode: RemoteInputMode = RemoteInputMode.DIRECT_IMAGE,
) {
    fun isValid(): Boolean =
        (baseUrl.trim().startsWith("http://") || baseUrl.trim().startsWith("https://")) && modelId.isNotBlank()
}
