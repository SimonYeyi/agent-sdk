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
include(":demos:realtime")

include(":agent:core")
include(":agent:capability")
include(":agent:config")
include(":agent:session")
include(":agent:skill")
include(":agent:hook")
include(":agent:approval")
include(":agent:mcp")
include(":agent:subagent")
include(":agent:toolset")
include(":agent:tool:serialization")
include(":agent:tool:compression")
include(":agent:tool:lazy-loading")
// genui 三级嵌套结构；叶级项目名 genui-core 与 :agent:core 避免同名，规避 Gradle 同名项目解析混淆
include(":agent:genui:genui-core")
project(":agent:genui:genui-core").projectDir = file("agent/genui/core")
include(":agent:genui:adapters:api")
include(":agent:genui:adapters:default")
include(":agent:genui:adapters:a2ui")
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

