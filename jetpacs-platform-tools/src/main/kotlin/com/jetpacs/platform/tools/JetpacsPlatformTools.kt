package com.jetpacs.platform.tools

import java.nio.file.Path
import kotlin.system.exitProcess

private const val VERSION = "0.4.0"

/**
 * Read-only developer workbench for the Jetpacs platform.
 *
 * The command line is intentionally useful without an MCP host:
 *
 *     jetpacs-platform-tools doctor
 *     jetpacs-platform-tools overview
 *     jetpacs-platform-tools summarize companion/wire/src/...
 *     jetpacs-platform-tools contract /methods/surface.update
 *
 * With no command, or with `serve`, the process starts its MCP stdio server.
 */
fun main(rawArgs: Array<String>) {
    val parsed = try {
        CliArguments.parse(rawArgs.toList())
    } catch (error: UserInputException) {
        System.err.println("jetpacs-platform-tools: ${error.message}")
        exitProcess(2)
    }
    if (parsed.help) {
        println(CliArguments.usage())
        return
    }

    val workspace = try {
        Workspace.open(parsed.root, parsed.orgSource, parsed.repositoriesRoot)
    } catch (error: Exception) {
        System.err.println("jetpacs-platform-tools: ${error.message}")
        exitProcess(2)
    }
    val workbench = JetpacsWorkbench(workspace)

    try {
        when (parsed.command) {
            "serve" -> {
                System.err.println(
                    "jetpacs-platform-tools $VERSION: serving ${workspace.root} over MCP stdio",
                )
                McpServer(workbench, VERSION).serve(System.`in`.bufferedReader(), System.out.bufferedWriter())
            }

            "doctor" -> println(workbench.doctor())
            "overview" -> println(workbench.projectOverview())
            "summarize" -> {
                val path = parsed.operands.firstOrNull()
                    ?: throw UserInputException("summarize requires a workspace-relative path")
                println(workbench.sourceSummary(path, includePrivate = parsed.includePrivate))
            }

            "contract" -> println(workbench.contractQuery(parsed.operands.firstOrNull(), null))
            else -> throw UserInputException("unknown command '${parsed.command}'")
        }
    } catch (error: UserInputException) {
        System.err.println("jetpacs-platform-tools: ${error.message}")
        exitProcess(2)
    }
}

private data class CliArguments(
    val command: String,
    val root: Path?,
    val orgSource: Path?,
    val repositoriesRoot: Path?,
    val operands: List<String>,
    val includePrivate: Boolean,
    val help: Boolean,
) {
    companion object {
        fun parse(args: List<String>): CliArguments {
            var root: Path? = null
            var orgSource: Path? = null
            var repositoriesRoot: Path? = null
            var includePrivate = false
            var help = false
            val positional = mutableListOf<String>()
            var index = 0
            while (index < args.size) {
                when (val argument = args[index]) {
                    "--root" -> {
                        val value = args.getOrNull(index + 1)
                            ?: throw UserInputException("--root requires a path")
                        root = Path.of(value)
                        index += 2
                    }

                    "--org-source" -> {
                        val value = args.getOrNull(index + 1)
                            ?: throw UserInputException("--org-source requires a path")
                        orgSource = Path.of(value)
                        index += 2
                    }

                    "--repositories-root" -> {
                        val value = args.getOrNull(index + 1)
                            ?: throw UserInputException("--repositories-root requires a path")
                        repositoriesRoot = Path.of(value)
                        index += 2
                    }

                    "--include-private" -> {
                        includePrivate = true
                        index += 1
                    }

                    "-h", "--help" -> {
                        help = true
                        index += 1
                    }

                    else -> {
                        if (argument.startsWith("-")) {
                            throw UserInputException("unknown option '$argument'")
                        }
                        positional += argument
                        index += 1
                    }
                }
            }
            return CliArguments(
                command = positional.firstOrNull() ?: "serve",
                root = root,
                orgSource = orgSource,
                repositoriesRoot = repositoriesRoot,
                operands = positional.drop(1),
                includePrivate = includePrivate,
                help = help,
            )
        }

        fun usage(): String =
            """
            |Jetpacs Platform Tools $VERSION — read-only platform developer workbench
            |
            |Usage: jetpacs-platform-tools [--root PATH] [--repositories-root PATH] [--org-source PATH] COMMAND [ARGS]
            |
            |Commands:
            |  serve                         Start the MCP stdio server (default)
            |  doctor                        Check that the Jetpacs source-of-truth artifacts exist
            |  overview                      Summarize modules and implementation areas
            |  summarize PATH                Produce a token-efficient Kotlin or Elisp outline
            |  contract [JSON_POINTER]        Inspect the active EBP contract
            |
            |Options:
            |  --include-private              Include private declarations in source outlines
            |  --org-source PATH               Allowlist an Emacs lisp/org source checkout as org/
            |  --repositories-root PATH        Allowlist the parent containing named sibling repositories
            |  --root PATH                    Override JETPACS_WORKSPACE/current-directory discovery
            |  -h, --help                     Show this help
            """.trimMargin()
    }
}

internal class UserInputException(message: String) : IllegalArgumentException(message)
