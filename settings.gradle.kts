pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "agent-sdk"

include(":agent")
include(":capability")
include(":session")
include(":skill")
include(":hook")
include(":mcp")
include(":subagent")
include(":toolset")
include(":providers:openai")
include(":providers:anthropic")
include(":app")

// Gateway: 平台接入层(从 HermesApp 移植,2026-06-24)
include(":gateway:core")
include(":gateway:platforms:feishu")
include(":gateway:platforms:telegram")
include(":gateway:platforms:weixin")
include(":gateway:app")
include(":gateway:jvm")
