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
        maven { url = uri("https://artifact.bytedance.com/repository/Volcengine/") }
    }
}

rootProject.name = "agent-sdk"

include(":demos:agent")
include(":demos:team")

include(":agent:core")
include(":agent:capability")
include(":agent:session")
include(":agent:skill")
include(":agent:hook")
include(":agent:approval")
include(":agent:mcp")
include(":agent:subagent")
include(":agent:toolset")
include(":agent:tool:serialization")
include(":agent:tool:compression")
include(":agent:providers:openai")
include(":agent:providers:anthropic")

include(":gateway:app")
include(":gateway:jvm")
include(":gateway:core")
include(":gateway:platforms:feishu")
include(":gateway:platforms:telegram")
include(":gateway:platforms:weixin")

include(":team")

include(":realtime:core")
include(":realtime:providers:volc")
include(":realtime:audio:android")
include(":realtime:providers:volc-android")

