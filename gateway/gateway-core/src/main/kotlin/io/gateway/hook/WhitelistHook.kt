package io.gateway.hook

import io.gateway.api.HookPipeline

class WhitelistHook(
    private val allowedUsers: Set<String> = emptySet(),
    private val allowedChats: Set<String> = emptySet()
) : HookPipeline.Hook {

    override val name: String = "whitelist"

    override val events: Set<HookPipeline.Event> = setOf(HookPipeline.Event.BEFORE_VALIDATE)

    override val priority: Int = 10

    override suspend fun execute(context: HookPipeline.Context): HookPipeline.Result {
        val message = context.message ?: return HookPipeline.Result.Continue

        val userId = message.source.userId
        val chatId = message.source.chatId

        val userAllowed = allowedUsers.isEmpty() || userId in allowedUsers
        val chatAllowed = allowedChats.isEmpty() || chatId in allowedChats

        return if (userAllowed && chatAllowed) {
            HookPipeline.Result.Continue
        } else {
            HookPipeline.Result.Halt("User $userId or chat $chatId not in whitelist")
        }
    }
}
