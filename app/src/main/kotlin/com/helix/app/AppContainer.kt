package com.helix.app

import android.content.Context
import com.helix.app.chat.ChatService
import com.helix.app.internal.PrefsLineStore
import com.helix.app.profile.AdvancedProfileAvailability
import com.helix.app.profile.PersistedSafetyProfileStore
import com.helix.app.profile.SafetyProfileStore
import com.helix.app.provider.CleartextBindingStore
import com.helix.app.provider.ProviderFactory
import com.helix.app.provider.ProviderService
import com.helix.app.provider.ProviderTestStatusStore
import com.helix.core.model.IdGenerator
import com.helix.core.model.RandomIdGenerator
import com.helix.core.model.SystemClock
import com.helix.core.storage.HelixStorage
import com.helix.provider.api.CredentialLookup

/**
 * The app's manual DI container (M0 pattern; no framework). HXA-028 adds the
 * production provider/chat stack: one shared [HelixStorage] (recovery +
 * providers + sessions), the safety-profile store, and the two services the
 * UI talks to. The UI never sees DAOs, OkHttp or the Keystore directly
 * (AGENTS.md; doc 02 section 16).
 */
interface AppContainer {
    val shellRepository: ShellRepository

    val storage: HelixStorage

    val profileStore: SafetyProfileStore

    val firstLaunch: FirstLaunchStore

    val providerService: ProviderService

    val chatService: ChatService
}

internal class DefaultAppContainer(
    context: Context,
) : AppContainer {
    override val shellRepository: ShellRepository = FakeShellRepository()

    override val storage: HelixStorage = HelixStorage.create(context)

    private val lineStore = PrefsLineStore(context, PREFS_NAME)

    override val profileStore: SafetyProfileStore =
        PersistedSafetyProfileStore(lineStore, AdvancedProfileAvailability.ADVANCED_AVAILABLE)

    override val firstLaunch: FirstLaunchStore = FirstLaunchStore(lineStore)

    private val idGenerator: IdGenerator = RandomIdGenerator()

    /**
     * Request-time credential resolution (HXA-025 seam): the Keystore secret is
     * read when the wire request is built — never at UI construction, never
     * into UI state (NFR-007). Keyless providers use the fixed non-secret
     * placeholder (their servers ignore the auth header).
     */
    private val credentials: CredentialLookup =
        CredentialLookup { alias ->
            if (alias.value == ProviderFactory.NO_KEY_ALIAS) {
                ProviderFactory.NO_KEY_PLACEHOLDER
            } else {
                storage.secrets.get(alias)
            }
        }

    override val providerService: ProviderService =
        ProviderService(
            storage = storage,
            factory = ProviderFactory(credentials, ProviderFactory.defaultWire()),
            bindings = CleartextBindingStore(lineStore),
            testStatus = ProviderTestStatusStore(lineStore),
            idGenerator = { idGenerator.next() },
        ).also { it.refresh() }

    override val chatService: ChatService =
        ChatService(
            storage = storage,
            providerService = providerService,
            profileStore = profileStore,
            clock = SystemClock(),
            idGenerator = { idGenerator.next() },
        )

    private companion object {
        const val PREFS_NAME = "helix-ui"
    }
}
