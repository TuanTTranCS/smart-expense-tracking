package com.hugo.smartexpense.app.graphauth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OneDrivePresentationTest {
    @Test fun signedOutOffersConnectAndIsNotReady() {
        val result = GraphAuthenticationUiState(status = GraphAuthenticationStatus.SIGNED_OUT, busy = false)
            .toOneDrivePresentation()
        assertEquals(OneDriveRecoveryAction.CONNECT, result.recoveryAction)
        assertFalse(result.readyForExport)
    }

    @Test fun connectedAccountMustVerifyFolderBeforeExport() {
        val result = GraphAuthenticationUiState(
            status = GraphAuthenticationStatus.SIGNED_IN, busy = false, accountDisplayName = "Hugo",
        ).toOneDrivePresentation()
        assertEquals(OneDriveRecoveryAction.VERIFY, result.recoveryAction)
        assertFalse(result.readyForExport)
    }

    @Test fun verifiedFolderIsTheSingleExportReadinessRule() {
        val result = GraphAuthenticationUiState(
            status = GraphAuthenticationStatus.SIGNED_IN, busy = false, folderVerified = true,
        ).toOneDrivePresentation()
        assertEquals(OneDriveRecoveryAction.NONE, result.recoveryAction)
        assertTrue(result.readyForExport)
    }
}
