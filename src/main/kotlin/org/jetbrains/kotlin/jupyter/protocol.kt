package org.jetbrains.kotlin.jupyter

import com.beust.klaxon.JsonObject
import jupyter.kotlin.DisplayResult
import jupyter.kotlin.MimeTypedResult
import jupyter.kotlin.textResult
import org.jetbrains.kotlin.config.KotlinCompilerVersion
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicLong

enum class ResponseState {
    Ok, Error
}

data class ResponseWithMessage(val state: ResponseState, val result: MimeTypedResult?, val displays: List<MimeTypedResult> = emptyList(), val stdOut: String? = null, val stdErr: String? = null) {
    val hasStdOut: Boolean = stdOut != null && stdOut.isNotEmpty()
    val hasStdErr: Boolean = stdErr != null && stdErr.isNotEmpty()
}

fun JupyterConnection.Socket.shellMessagesHandler(msg: Message, repl: ReplForJupyter?, executionCount: AtomicLong) {
    val msgType = msg.header!!["msg_type"]
    when (msgType) {
        "kernel_info_request" ->
            sendWrapped(msg, makeReplyMessage(msg, "kernel_info_reply",
                    content = jsonObject(
                            "protocol_version" to protocolVersion,
                            "language" to "Kotlin",
                            "language_version" to KotlinCompilerVersion.VERSION,
                            "language_info" to jsonObject(
                                    "name" to "kotlin",
                                    "codemirror_mode" to "text/x-kotlin",
                                    "file_extension" to ".kt",
                                    "mimetype" to "text/x-kotlin",
                                    "pygments_lexer" to "kotlin",
                                    "version" to KotlinCompilerVersion.VERSION
                            ),

                            // Jupyter lab Console support
                            "banner" to "Kotlin language, version ${KotlinCompilerVersion.VERSION}",
                            "implementation" to "Kotlin",
                            "implementation_version" to runtimeProperties.version,
                            "status" to "ok"
                    )))
        "history_request" ->
            sendWrapped(msg, makeReplyMessage(msg, "history_reply",
                    content = jsonObject(
                            "history" to listOf<String>() // not implemented
                    )))
        "shutdown_request" -> {
            sendWrapped(msg, makeReplyMessage(msg, "shutdown_reply", content = msg.content))
            Thread.currentThread().interrupt()
        }
        "connect_request" ->
            sendWrapped(msg, makeReplyMessage(msg, "connection_reply",
                    content = jsonObject(JupyterSockets.values()
                            .map { Pair("${it.name}_port", connection.config.ports[it.ordinal]) })))
        "execute_request" -> {
            connection.contextMessage = msg
            val count = executionCount.getAndIncrement()
            val startedTime = ISO8601DateNow

            connection.iopub.send(makeReplyMessage(msg, "status", content = jsonObject("execution_state" to "busy")))
            val code = msg.content["code"]
            connection.iopub.send(makeReplyMessage(msg, "execute_input", content = jsonObject(
                    "execution_count" to count,
                    "code" to code)))
            val res: ResponseWithMessage = if (isCommand(code.toString())) {
                runCommand(code.toString(), repl)
            } else {
                connection.evalWithIO {
                    repl?.eval(code.toString(), count.toInt())
                }
            }

            fun sendOut(stream: String, text: String) {
                connection.iopub.send(makeReplyMessage(msg, header = makeHeader("stream", msg),
                        content = jsonObject(
                                "name" to stream,
                                "text" to text)))
            }

            if (res.hasStdOut) {
                sendOut("stdout", res.stdOut!!)
            }
            if (res.hasStdErr) {
                sendOut("stderr", res.stdErr!!)
            }

            when (res.state) {
                ResponseState.Ok -> {
                    if (res.result != null) {
                        connection.iopub.send(makeReplyMessage(msg,
                                "execute_result",
                                content = jsonObject(
                                        "execution_count" to count,
                                        "data" to res.result,
                                        "metadata" to jsonObject()
                                )))
                    }
                    res.displays.forEach {
                        connection.iopub.send(makeReplyMessage(msg,
                                "display_data",
                                content = jsonObject(
                                        "data" to it,
                                        "metadata" to jsonObject()
                                )))
                    }

                    send(makeReplyMessage(msg, "execute_reply",
                            metadata = jsonObject(
                                    "dependencies_met" to true,
                                    "engine" to msg.header["session"],
                                    "status" to "ok",
                                    "started" to startedTime),
                            content = jsonObject(
                                    "status" to "ok",
                                    "execution_count" to count,
                                    "user_variables" to JsonObject(),
                                    "payload" to listOf<String>(),
                                    "user_expressions" to JsonObject())))
                }
                ResponseState.Error -> {
                    val errorReply = makeReplyMessage(msg, "execute_reply",
                            content = jsonObject(
                                    "status" to "abort",
                                    "execution_count" to count))
                    System.err.println("Sending abort: $errorReply")
                    send(errorReply)
                }
            }

            connection.iopub.send(makeReplyMessage(msg, "status", content = jsonObject("execution_state" to "idle")))
            connection.contextMessage = null
        }
        "comm_info_request" -> {
            sendWrapped(msg, makeReplyMessage(msg, "comm_info_reply",  content = jsonObject("comms" to jsonObject())))
        }
        "complete_request" -> {
            val code = msg.content["code"].toString()
            val cursor = msg.content["cursor_pos"] as Int
            val result = repl?.complete(code, cursor)?.toJson()
            if (result == null) {
                System.err.println("Repl is not yet initialized on complete request")
                return
            }
            sendWrapped(msg, makeReplyMessage(msg, "complete_reply",  content = result))
        }
        "is_complete_request" -> {
            val code = msg.content["code"].toString()
            val resStr = if (isCommand(code)) "complete" else {
                val result = try {
                   val check = repl?.checkComplete(executionCount.get(), code)
                    when {
                        check == null -> "error: no repl"
                        check.isComplete -> "complete"
                        else -> "incomplete"
                    }
                } catch (ex: ReplCompilerException) {
                    "invalid"
                }
                result
            }
            sendWrapped(msg, makeReplyMessage(msg, "is_complete_reply", content = jsonObject("status" to resStr)))
        }
        else -> send(makeReplyMessage(msg, "unsupported_message_reply"))
    }
}

class CapturingOutputStream(val stdout: PrintStream, val captureOutput: Boolean) : OutputStream() {
    val capturedOutput = ByteArrayOutputStream()

    override fun write(b: Int) {
        stdout.write(b)
        if (captureOutput) capturedOutput.write(b)
    }
}

fun Any.toMimeTypedResult(): MimeTypedResult? = when (this) {
    is MimeTypedResult -> this
    is Unit -> null
    is DisplayResult -> value.toMimeTypedResult()
    else -> textResult(this.toString())
}

fun JupyterConnection.evalWithIO(body: () -> EvalResult?): ResponseWithMessage {
    val out = System.out
    val err = System.err

    // TODO: make configuration option of whether to pipe back stdout and stderr
    // TODO: make a configuration option to limit the total stdout / stderr possibly returned (in case it goes wild...)
    val forkedOut = CapturingOutputStream(out, true)
    val forkedError = CapturingOutputStream(err, false)

    System.setOut(PrintStream(forkedOut, true, "UTF-8"))
    System.setErr(PrintStream(forkedError, true, "UTF-8"))

    val `in` = System.`in`
    System.setIn(stdinIn)
    try {
        return try {
            val exec = body()
            if (exec == null) {
                ResponseWithMessage(ResponseState.Error, textResult("Error!"), emptyList(), null, "NO REPL!")
            } else {
                val stdOut = forkedOut.capturedOutput.toString("UTF-8").emptyWhenNull()
                val stdErr = forkedError.capturedOutput.toString("UTF-8").emptyWhenNull()

                try {
                    var result: MimeTypedResult? = null
                    var displays = exec.displayValues.mapNotNull { it.toMimeTypedResult() }
                    if (exec.resultValue is DisplayResult) {
                        val resultDisplay = exec.resultValue.value.toMimeTypedResult()
                        if (resultDisplay != null)
                            displays += resultDisplay
                    } else result = exec.resultValue?.toMimeTypedResult()
                    ResponseWithMessage(ResponseState.Ok, result, displays, stdOut, stdErr)
                } catch (e: Exception) {
                    ResponseWithMessage(ResponseState.Error, textResult("Error!"), emptyList(), stdOut,
                            joinLines(stdErr, "error:  Unable to convert result to a string: ${e}"))
                }
            }
        } catch (ex: ReplCompilerException) {
            val stdOut = forkedOut.capturedOutput.toString("UTF-8").emptyWhenNull()
            val stdErr = forkedError.capturedOutput.toString("UTF-8").emptyWhenNull()

            // handle runtime vs. compile time and send back correct format of response, now we just send text
            /*
                {
                   'status' : 'error',
                   'ename' : str,   # Exception name, as a string
                   'evalue' : str,  # Exception value, as a string
                   'traceback' : list(str), # traceback frames as strings
                }
             */
            ResponseWithMessage(ResponseState.Error, textResult("Error!"), emptyList(), stdOut,
                    joinLines(stdErr, ex.errorResult.message))
        } catch (ex: ReplEvalRuntimeException) {
            val stdOut = forkedOut.capturedOutput.toString("UTF-8").emptyWhenNull()

            // handle runtime vs. compile time and send back correct format of response, now we just send text
            /*
                {
                   'status' : 'error',
                   'ename' : str,   # Exception name, as a string
                   'evalue' : str,  # Exception value, as a string
                   'traceback' : list(str), # traceback frames as strings
                }
             */
            val stdErr = StringBuilder()
            with(stdErr) {
                forkedError.capturedOutput.toString("UTF-8")?.nullWhenEmpty()?.also { appendln(it) }
                val cause = ex.errorResult.cause
                if (cause == null) appendln(ex.errorResult.message)
                else {
                    when (cause) {
                        is InvocationTargetException -> appendln(cause.targetException.toString())
                        else -> appendln(cause.toString())
                    }
                    cause.stackTrace?.also {
                        for (s in it)
                            appendln(s)
                    }
                }
            }
            ResponseWithMessage(ResponseState.Error, textResult("Error!"), emptyList(), stdOut, stdErr.toString())
        }
    } finally {
        System.setIn(`in`)
        System.setErr(err)
        System.setOut(out)
    }
}

fun joinLines(vararg parts: String): String = parts.filter(String::isNotBlank).joinToString("\n")
fun String.nullWhenEmpty(): String? = if (this.isBlank()) null else this
fun String?.emptyWhenNull(): String = if (this == null || this.isBlank()) "" else this

