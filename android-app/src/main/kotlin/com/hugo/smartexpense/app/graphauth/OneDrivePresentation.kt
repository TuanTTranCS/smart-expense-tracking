package com.hugo.smartexpense.app.graphauth

enum class OneDriveRecoveryAction { NONE, CONNECT, RECONNECT, VERIFY }

data class OneDrivePresentation(
    val summary: String,
    val detail: String,
    val readyForExport: Boolean,
    val recoveryAction: OneDriveRecoveryAction,
    val recoveryLabel: String? = null,
)

fun GraphAuthenticationUiState.toOneDrivePresentation(): OneDrivePresentation = when {
    status == GraphAuthenticationStatus.INITIALIZING -> OneDrivePresentation(
        "OneDrive is initializing", "Checking your Microsoft account.", false, OneDriveRecoveryAction.NONE,
    )
    busy -> OneDrivePresentation(
        "OneDrive operation in progress", message ?: "Please wait.", false, OneDriveRecoveryAction.NONE,
    )
    status == GraphAuthenticationStatus.SIGNED_OUT -> OneDrivePresentation(
        "OneDrive is disconnected", message ?: "Connect before exporting a receipt.", false,
        OneDriveRecoveryAction.CONNECT, "Connect Microsoft account",
    )
    status == GraphAuthenticationStatus.INTERACTION_REQUIRED -> OneDrivePresentation(
        "Microsoft account needs attention", message ?: "Reconnect before exporting.", false,
        OneDriveRecoveryAction.RECONNECT, "Reconnect",
    )
    status == GraphAuthenticationStatus.ERROR && accountDisplayName == null -> OneDrivePresentation(
        "OneDrive is unavailable", message ?: "Connect your Microsoft account again.", false,
        OneDriveRecoveryAction.CONNECT, "Connect Microsoft account",
    )
    !folderVerified -> OneDrivePresentation(
        "OneDrive access is not verified",
        message ?: "Verify the expense handoff folder before exporting.",
        false, OneDriveRecoveryAction.VERIFY, "Verify OneDrive access",
    )
    else -> OneDrivePresentation(
        "OneDrive is ready", message ?: "Receipt exports can be uploaded.", true, OneDriveRecoveryAction.NONE,
    )
}
