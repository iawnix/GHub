package dev.remotegpt.client

/** Fixed diagnostic categories: never include server messages, addresses or credentials. */
enum class ClientErrorCode {
    INVALID_SSH_ENDPOINT,
    MISSING_PRIVATE_KEY,
    PROXY_CONNECT_FAILED,
    CONNECTION_CLOSED,
    REQUEST_EXPIRED,
    UNSUPPORTED_APPROVAL,
    APPROVAL_ALREADY_SUBMITTED,
    UNSUPPORTED_QUESTION,
    INCOMPLETE_ANSWERS,
    INVALID_OPTION_ANSWER,
    ANSWER_ALREADY_SUBMITTED,
    THREAD_ID_MISMATCH,
    INVALID_REMOTE_DIRECTORY,
    PATH_OCCUPIED_BY_FILE,
    TEXT_TOO_LARGE_TO_EDIT,
    NOT_EDITABLE_TEXT,
    UNSUPPORTED_TEXT_ENCODING,
    TEXT_EXCEEDS_SAVE_LIMIT,
    PREVIEW_TOO_LARGE,
    REPEATED_MODEL_CURSOR,
}

class ClientArgumentException(val code: ClientErrorCode, message: String) : IllegalArgumentException(message)
class ClientStateException(val code: ClientErrorCode, message: String) : IllegalStateException(message)

class EventBacklog : Exception("Client event backlog exceeded; resynchronizing session.")
class ProxyClosed(val code: Int, val remote: Boolean, val heartbeat: Boolean) : Exception("Proxy WebSocket closed ($code)")
class HostKeyRejected : Exception("Host key fingerprint was not trusted.")
class DaemonNotRunning : Exception("Codex App Server daemon is not running.")
class RemoteCommandFailed : Exception("Remote Codex command exited with an error.")
class RemoteCommandTimeout : Exception("Remote Codex command timed out.")

fun connectionDiagnostic(error: Throwable?): String {
    val causes = generateSequence(error) { it.cause }.take(8).toList()
    val proxy = causes.filterIsInstance<ProxyClosed>().firstOrNull()
    return when {
        causes.any { it is EventBacklog } -> "event_backlog"
        causes.any { it is HostKeyRejected } -> "host_key_rejected"
        causes.any { it is UnsupportedServer } -> "incompatible_version"
        causes.any { it is DaemonNotRunning } -> "daemon_not_running"
        causes.any { it.javaClass.simpleName == "UserAuthException" } -> "ssh_authentication"
        proxy != null -> "websocket_close code=${proxy.code} remote=${proxy.remote} heartbeat=${proxy.heartbeat}"
        causes.any { it is RemoteCommandFailed } -> "remote_command_failed"
        causes.any { it is RemoteCommandTimeout || it is java.net.SocketTimeoutException || it is kotlinx.coroutines.TimeoutCancellationException } -> "timeout"
        causes.any { it.javaClass.simpleName == "TransportException" } -> "ssh_transport"
        causes.any { it is kotlinx.serialization.SerializationException } -> "protocol_decode"
        error == null -> "closed"
        else -> error.javaClass.simpleName
    }
}
