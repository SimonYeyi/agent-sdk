package io.github.yeyi.agent.subagent

import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.agent
import io.github.yeyi.agent.awaitResult
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.memory.Memory

/**
 * [Subagent] 的默认实现。
 */
public class ReActSubagent(
    override val name: String,
    override val description: String,
    private val instructions: String,
    private val maxIterations: Int,
    private val sharedMemory: Memory?,
) : Subagent {

    override suspend fun activate(
        arguments: SubagentTask?,
        context: SubagentContext
    ): String {
        val task = arguments?.task
            ?: throw IllegalArgumentException("Missing 'task' argument")

        val memory = sharedMemory ?: InMemoryMemory()

        val sub = agent {
            persona(Persona(instructions))
            llmProvider(context.agentContext.llmProvider)
            // hook(context.agentContext.hook)
            memory(memory)
            maxIterations(maxIterations)
        }

        return sub.run(task).awaitResult().message.content
            ?: throw IllegalStateException("Subagent '${name}' returned empty content")
    }
}
