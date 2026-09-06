pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        exclusiveContent {
            forRepository {
                maven {
                    name = "JitPackLibsu"
                    url = uri("https://jitpack.io")
                }
            }
            filter {
                includeGroup("com.github.topjohnwu.libsu")
            }
        }
    }
}

rootProject.name = "Helix"

include(
    ":app",
    ":core:model",
    ":core:agent",
    ":core:policy",
    ":core:storage",
    ":core:workspace",
    ":provider:api",
    ":provider:openai-responses",
    ":provider:openai-chat",
    ":provider:anthropic",
    ":provider:catalog",
    ":extensions:mcp",
    ":extensions:a2a",
    ":extensions:skills",
    ":feature:browser",
    ":feature:files",
    ":feature:files-allfiles",
    ":runtime:quickjs",
    ":runtime:proot-core",
    ":runtime:proot-ipc",
    ":runtime:proot-client",
    ":runtime:proot-app",
    ":runtime:cli-client",
    ":runtime:cli-app",
    ":spikes:a2a-sdk",
    ":spikes:a2a-minimal",
    ":spikes:bounded-orchestration",
    ":tools:framework",
    ":tools:android",
    ":tools:automation",
    ":tools:browser",
    ":tools:files",
    ":tools:root",
    ":testing",
)
