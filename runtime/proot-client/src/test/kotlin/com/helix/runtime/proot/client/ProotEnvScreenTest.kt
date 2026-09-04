package com.helix.runtime.proot.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HXA-084: the job environment screen. Task requirement: 任务自定义 env 对
 * 名称 secret pattern、已知 SecretStore 值和认证来源做拒绝测试, Runtime 不获得
 * SecretStore 能力. Rejections must name variables, never values.
 */
class ProotEnvScreenTest {
    @Test
    fun `allowlisted plain names are admitted`() {
        val verdict =
            ProotEnvScreen.screen(
                mapOf(
                    "HOME" to "/workspace",
                    "PATH" to "/usr/bin:/bin",
                    "LANG" to "C.UTF-8",
                    "TERM" to "xterm-256color",
                ),
                knownSecretValues = setOf("hunter2"),
            )
        val expected =
            mapOf(
                "HOME" to "/workspace",
                "PATH" to "/usr/bin:/bin",
                "LANG" to "C.UTF-8",
                "TERM" to "xterm-256color",
            )
        assertEquals(ProotEnvScreen.Verdict.Approved(expected), verdict)
    }

    @Test
    fun `empty request is legal`() {
        val verdict = ProotEnvScreen.screen(emptyMap(), knownSecretValues = setOf("hunter2"))
        assertEquals(ProotEnvScreen.Verdict.Approved(emptyMap()), verdict)
    }

    @Test
    fun `secret-named variables are rejected regardless of allowlist`() {
        val verdict =
            ProotEnvScreen.screen(
                mapOf(
                    "API_KEY" to "abc",
                    "GIT_TOKEN" to "abc",
                    "DB_PASSWORD" to "abc",
                    "SESSION_COOKIE" to "abc",
                    "OAUTH_CREDENTIAL" to "abc",
                    "X_AUTH_HEADER" to "abc",
                    "MY_SECRET" to "abc",
                ),
                knownSecretValues = emptySet(),
                // Even an explicit addition cannot admit a secret-named variable.
                extraAllowedNames = setOf("API_KEY"),
            )
        val rejected = verdict as ProotEnvScreen.Verdict.Rejected
        assertEquals(
            setOf(
                "API_KEY",
                "GIT_TOKEN",
                "DB_PASSWORD",
                "SESSION_COOKIE",
                "OAUTH_CREDENTIAL",
                "X_AUTH_HEADER",
                "MY_SECRET",
            ),
            rejected.reasons.map { it.name }.toSet(),
        )
        assertTrue(rejected.reasons.all { it.kind == ProotEnvScreen.Reason.Kind.SECRET_NAME })
    }

    @Test
    fun `unknown names are rejected with NOT_ALLOWED`() {
        val verdict = ProotEnvScreen.screen(mapOf("WEIRD_VAR" to "1"), knownSecretValues = emptySet())
        val rejected = verdict as ProotEnvScreen.Verdict.Rejected
        assertEquals(
            ProotEnvScreen.Reason(ProotEnvScreen.Reason.Kind.NOT_ALLOWED, "WEIRD_VAR"),
            rejected.reasons.single(),
        )
    }

    @Test
    fun `task-explicit names are admitted when they pass the screen`() {
        val verdict =
            ProotEnvScreen.screen(
                mapOf("CARGO_HOME" to "/workspace/cargo"),
                knownSecretValues = emptySet(),
                extraAllowedNames = setOf("CARGO_HOME"),
            )
        assertEquals(
            ProotEnvScreen.Verdict.Approved(mapOf("CARGO_HOME" to "/workspace/cargo")),
            verdict,
        )
    }

    @Test
    fun `known secret values are rejected by value comparison`() {
        val verdict =
            ProotEnvScreen.screen(
                // A renamed variable: the name is clean, the value is the known secret.
                mapOf("EDITOR_PREF" to "hunter2"),
                knownSecretValues = setOf("hunter2"),
            )
        val rejected = verdict as ProotEnvScreen.Verdict.Rejected
        assertEquals(
            ProotEnvScreen.Reason(ProotEnvScreen.Reason.Kind.KNOWN_SECRET_VALUE, "EDITOR_PREF"),
            rejected.reasons.single(),
        )
    }

    @Test
    fun `authorization structures are rejected by shape`() {
        val verdict =
            ProotEnvScreen.screen(
                mapOf(
                    "BEARER" to "Bearer token-value-abcdefghij.1234567890.uvwxyz",
                    "BASIC" to "Basic dXNlcjpwdw==",
                    "HDR" to "Authorization: Bearer x",
                    "PEM" to "-----BEGIN RSA " + "PRIVATE KEY-----\nabc\n-----END RSA PRIVATE KEY-----",
                ),
                knownSecretValues = emptySet(),
                extraAllowedNames = setOf("BEARER", "BASIC", "HDR", "PEM"),
            )
        val rejected = verdict as ProotEnvScreen.Verdict.Rejected
        assertEquals(4, rejected.reasons.size)
        assertTrue(rejected.reasons.all { it.kind == ProotEnvScreen.Reason.Kind.AUTH_STRUCTURE })
    }

    @Test
    fun `rejection reasons never contain values`() {
        val verdict =
            ProotEnvScreen.screen(
                mapOf(
                    "API_KEY" to "TOPSECRETVALUE123",
                    "HOME" to "TOPSECRETVALUE123",
                ),
                knownSecretValues = setOf("TOPSECRETVALUE123"),
            )
        val rejected = verdict as ProotEnvScreen.Verdict.Rejected
        // The HOME entry is allowed by name, but its value is the known secret.
        assertEquals(setOf("API_KEY", "HOME"), rejected.reasons.map { it.name }.toSet())
        for (reason in rejected.reasons) {
            assertTrue("reason must not carry the value", !reason.toString().contains("TOPSECRETVALUE123"))
        }
    }

    @Test
    fun `mixed request rejects all offenders at once`() {
        val verdict =
            ProotEnvScreen.screen(
                mapOf(
                    "HOME" to "/workspace",
                    "TOKEN" to "t",
                    "UNKNOWN_ONE" to "u",
                ),
                knownSecretValues = emptySet(),
            )
        val rejected = verdict as ProotEnvScreen.Verdict.Rejected
        assertEquals(
            setOf(
                ProotEnvScreen.Reason(ProotEnvScreen.Reason.Kind.SECRET_NAME, "TOKEN"),
                ProotEnvScreen.Reason(ProotEnvScreen.Reason.Kind.NOT_ALLOWED, "UNKNOWN_ONE"),
            ),
            rejected.reasons.toSet(),
        )
    }
}
