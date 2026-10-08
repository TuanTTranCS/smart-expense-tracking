package com.hugo.smartexpense.app.receipt.ui

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

/** Activity recreation preserves the retained ViewModel run; an actual stop requires explicit resume. */
class ReceiptWorkflowLifecycleObserver(
    private val setForeground: (Boolean) -> Unit,
    private val isChangingConfigurations: () -> Boolean,
) : LifecycleEventObserver {
    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_RESUME -> setForeground(true)
            Lifecycle.Event.ON_STOP -> if (!isChangingConfigurations()) setForeground(false)
            else -> Unit
        }
    }
}
