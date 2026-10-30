package com.helix.app.scheduled

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduledOpenRequestTest {
    @Test fun onlyClosedNavigationIdentityIsAccepted() {
        val id = "00000000-0000-0000-0000-000000000001"
        val request = "00000000-0000-0000-0000-000000000002"
        val url = "helix://automation/$id?requestId=$request"
        assertEquals(ScheduledOpenRequest(id, request), ScheduledOpenRequest.parse(url))
        listOf(
            "$url&prompt=execute",
            "$url&requestId=$request",
            "$url#fragment",
            url.replace("automation", "user@automation"),
            url.replace("automation", "automation:80"),
            url.replace(id, "%30${id.drop(1)}"),
            "helix://automation/$id",
            "$url/extra",
            "helix://automation/1?requestId=$request",
            url.replace("helix:", "https:"),
        ).forEach { assertNull(it, ScheduledOpenRequest.parse(it)) }
    }
}
