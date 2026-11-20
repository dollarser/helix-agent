package com.helix.app.provider

import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelFailureDiagnostic
import com.helix.core.model.ModelFailureDiagnostic.ServerError
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelDirectoryRejectionTest {
    @Test fun onlyExplicitModelErrorsRequestRefresh() {
        assertTrue(modelDirectoryRejected(error(ServerError.MODEL_NOT_FOUND)))
        assertTrue(modelDirectoryRejected(error(ServerError.PERMISSION_ERROR, "model")))
        assertFalse(modelDirectoryRejected(error(ServerError.PERMISSION_ERROR)))
        assertFalse(modelDirectoryRejected(error(ServerError.AUTHENTICATION_ERROR)))
        assertFalse(modelDirectoryRejected(error(ServerError.OVERLOADED_ERROR)))
        assertFalse(modelDirectoryRejected(error(null)))
        assertFalse(modelDirectoryRejected(ModelEvent.Error(ModelErrorCode.TIMEOUT, true)))
    }

    private fun error(
        server: ServerError?,
        parameter: String? = null,
    ) = ModelEvent.Error(
        ModelErrorCode.HTTP_ERROR,
        false,
        diagnostic =
            ModelFailureDiagnostic(
                ModelFailureDiagnostic.Stage.HTTP,
                ModelFailureDiagnostic.Reason.HTTP_STATUS,
                httpStatus = 404,
                serverError = server,
                errorParameter = parameter,
            ),
    )
}
