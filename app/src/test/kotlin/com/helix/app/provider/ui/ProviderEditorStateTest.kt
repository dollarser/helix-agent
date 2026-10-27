package com.helix.app.provider.ui

import com.helix.app.R
import com.helix.provider.catalog.ProviderTemplateCatalog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderEditorStateTest {
    private val form =
        ProviderForm(
            null,
            ProviderTemplateCatalog.sglang,
            ProviderForm.FormFields("Server", "https://example.test/v1", "model", "", "", "synthetic-key"),
            false,
            null,
        )

    @Test fun defaultFactoryCanCreateTheRouteScopedEditorWithoutSavedCredentials() {
        val state = ProviderEditorState::class.java.getDeclaredConstructor().newInstance()
        assertNull(state.form)
        assertFalse(state.saving)
    }

    @Test fun retainedEditorDoesNotResubmitInFlightSaveAndClosesOnlyAfterSuccess() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val state = ProviderEditorState(scope)
            state.form = form
            val reply = CompletableDeferred<SaveResult>()
            var calls = 0
            val save: suspend (ProviderForm) -> SaveResult = {
                calls++
                reply.await()
            }
            state.save(form, save)
            assertTrue(state.saving)
            assertEquals(form, state.form)
            state.save(form, save)
            assertEquals(1, calls)
            reply.complete(SaveResult.Saved)
            assertFalse(state.saving)
            assertNull(state.form)
        } finally {
            scope.cancel()
        }
    }

    @Test fun failureKeepsUserInputEditableAndRetryDoesNotReuseTheFailedResult() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val state = ProviderEditorState(scope)
            state.form = form
            state.save(form) { SaveResult.Rejected(R.string.provider_save_failed) }
            assertFalse(state.saving)
            val retained = requireNotNull(state.form)
            assertEquals(form.fields, retained.fields)
            assertEquals(R.string.provider_save_failed, retained.error?.res)
            state.save(retained) { SaveResult.Saved }
            assertNull(state.form)
        } finally {
            scope.cancel()
        }
    }

    @Test fun lateResultCannotCloseAnotherForm() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val state = ProviderEditorState(scope)
            state.form = form
            val reply = CompletableDeferred<SaveResult>()
            state.save(form) { reply.await() }
            val next = form.copy(fields = form.fields.copy(name = "Different"))
            state.form = next
            reply.complete(SaveResult.Saved)
            assertEquals(next, state.form)
            assertFalse(state.saving)
        } finally {
            scope.cancel()
        }
    }
}
