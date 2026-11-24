package dev.remotegpt.client

import kotlinx.coroutines.*
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.connection.channel.Channel
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive
import net.schmizz.sshj.userauth.method.ChallengeResponseProvider
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.security.PublicKey
import java.io.Closeable
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Callbacks are suspendable; host-key decisions are made before authentication. */
interface SshPrompts {
    suspend fun credential(id: String): Credential
    suspend fun verifyHostKey(host: String, port: Int, algorithm: String, fingerprint: String): Boolean
    suspend fun verifyHostKey(route: String, host: String, port: Int, algorithm: String, fingerprint: String): Boolean =
        verifyHostKey(host, port, algorithm, fingerprint)
    suspend fun keyboard(prompt: KeyboardPrompt): List<String>
}

/** Bridges SSHJ synchronous callbacks while propagating outer coroutine cancellation immediately. */
internal fun <T> runPromptInterruptibly(parent: Job?, block: suspend CoroutineScope.() -> T): T {
    val child = Job(parent)
    return try {
        runBlocking(child, block)
    } finally {
        child.cancel()
    }
}

fun hopRoutePrefix(jumps: List<SshEndpoint>, hopIndex: Int): String =
    jumps.take(hopIndex).joinToString("->") { "${it.host}:${it.port}" }

fun shellQuote(value: String): String {
    require('\u0000' !in value)
    return "'" + value.replace("'", "'\"'\"'") + "'"
}

class SshConnection private constructor(val profile: HostProfile, private val chain: List<SSHClient>) : Closeable {
    val client: SSHClient get() = chain.last()

    companion object {
        suspend fun connect(profile: HostProfile, prompts: SshPrompts): SshConnection {
            // Keep ownership outside withContext: cancellation can win while returning from IO.
            val chain = mutableListOf<SSHClient>()
            try {
                return withContext(Dispatchers.IO) {
                SshCrypto.configure()
                val callerJob = currentCoroutineContext()[Job]
                val endpoints = profile.jumps + profile.endpoint
                for ((hopIndex, endpoint) in endpoints.withIndex()) {
                    ensureActive()
                    if (endpoint.host.isBlank() || endpoint.username.isBlank() || endpoint.port !in 1..65535) {
                        throw ClientArgumentException(ClientErrorCode.INVALID_SSH_ENDPOINT, "Invalid SSH host, port, or username")
                    }
                    val route = hopRoutePrefix(profile.jumps, hopIndex)
                    val ssh = SSHClient()
                    val previous = chain.lastOrNull()
                    chain += ssh
                    ssh.connectTimeout = 20_000
                    ssh.timeout = 120_000 // allows host-key and interactive dialogs
                    ssh.addHostKeyVerifier(object : HostKeyVerifier {
                        override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                            val bytes = Buffer.PlainBuffer().putPublicKey(key).compactData
                            val fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))
                            if (!runPromptInterruptibly(callerJob) {
                                    prompts.verifyHostKey(route, endpoint.host, endpoint.port, key.algorithm, fingerprint)
                                }) throw HostKeyRejected()
                            return true
                        }
                        override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
                    })
                    val cancelHandle = callerJob?.invokeOnCompletion { cause ->
                        if (cause != null) runCatching { ssh.close() }
                    }
                    try {
                        if (previous == null) ssh.connect(endpoint.host, endpoint.port)
                        else ssh.connectVia(previous.newDirectConnection(endpoint.host, endpoint.port))
                        ensureActive()
                        val secret = prompts.credential(endpoint.credentialId)
                        when (endpoint.auth) {
                            AuthKind.PASSWORD -> ssh.authPassword(endpoint.username, secret.password)
                            AuthKind.PRIVATE_KEY -> {
                                if (secret.privateKey.isBlank()) {
                                    throw ClientArgumentException(ClientErrorCode.MISSING_PRIVATE_KEY, "Missing SSH private key")
                                }
                                val password = object : PasswordFinder {
                                    override fun reqPassword(resource: Resource<*>?): CharArray = secret.passphrase.toCharArray()
                                    override fun shouldRetry(resource: Resource<*>?) = false
                                }
                                ssh.authPublickey(endpoint.username, ssh.loadKeys(secret.privateKey, null, password))
                            }
                            AuthKind.INTERACTIVE -> ssh.auth(endpoint.username, AuthKeyboardInteractive(object : ChallengeResponseProvider {
                                private var title = ""
                                private var instruction = ""
                                override fun getSubmethods() = emptyList<String>()
                                override fun init(resource: Resource<*>?, name: String, instruction: String) { title = name; this.instruction = instruction }
                                override fun getResponse(prompt: String, echo: Boolean): CharArray = runPromptInterruptibly(callerJob) {
                                    prompts.keyboard(KeyboardPrompt(title, instruction, listOf(prompt to echo))).firstOrNull().orEmpty().toCharArray()
                                }
                                override fun shouldRetry() = false
                            }))
                        }
                    } finally {
                        cancelHandle?.dispose()
                    }
                    ensureActive()
                    ssh.connection.keepAlive.keepAliveInterval = 20
                }
                SshConnection(profile, chain)
                }
            } catch (e: Throwable) {
                withContext(NonCancellable + Dispatchers.IO) { chain.asReversed().forEach { runCatching { it.close() } } }
                throw e
            }
        }
    }

    suspend fun command(command: String): String = withContext(Dispatchers.IO) {
        client.startSession().use { session ->
            session.exec(command).use { process ->
                coroutineScope {
                    val errors = async { val buffer = ByteArray(4096); while (process.errorStream.read(buffer) >= 0) Unit }
                    val output = async { readLimited(process.inputStream, 1_048_576) }
                    withTimeout(30_000) {
                        process.join(25, TimeUnit.SECONDS)
                        if (process.exitStatus == null) { process.close(); throw RemoteCommandTimeout() }
                        val body = output.await().decodeToString()
                        errors.await()
                        if (process.exitStatus != 0) throw RemoteCommandFailed()
                        body
                    }
                }
            }
        }
    }

    suspend fun version(): String = command("${shellQuote(profile.codexPath)} app-server daemon version")
    suspend fun bootstrap(): String = command("${shellQuote(profile.codexPath)} app-server daemon bootstrap")

    fun proxy(): Session.Command {
        val session = client.startSession()
        return try {
            val socket = profile.socketPath.takeIf { it.isNotBlank() }?.let { " --sock ${shellQuote(it)}" }.orEmpty()
            session.exec("exec ${shellQuote(profile.codexPath)} app-server proxy$socket")
        } catch (e: Throwable) { session.close(); throw e }
    }

    suspend fun terminal(cols: Int = 80, rows: Int = 24, cwd: String = ""): RemoteTerminal {
        var session: Session? = null
        try {
            return withContext(Dispatchers.IO) {
                val opened = client.startSession().also { session = it }
                opened.allocatePTY("xterm-256color", cols, rows, 0, 0, emptyMap())
                // Enter the directory before the interactive login shell starts.
                // Sending cd to a live shell races its startup/line editor and
                // can echo twice or become input to a program launched by rc files.
                val channel = if (cwd.isBlank()) opened.startShell() else opened.exec(
                    "exec /bin/sh -c " + shellQuote("cd -- ${shellQuote(cwd)} && exec \"\${SHELL:-/bin/sh}\" -l"),
                )
                RemoteTerminal(opened, channel)
            }
        } catch (e: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { runCatching { session?.close() } }
            throw e
        }
    }

    override fun close() { chain.asReversed().forEach { runCatching { it.close() } } }
}

class RemoteTerminal(private val session: Session, private val shell: Channel) : Closeable {
    // SSHJ's SessionChannel implements both Shell and Command; PTY window
    // changes apply to either channel, but are exposed on the Shell interface.
    private val window = session as Session.Shell
    val input get() = shell.inputStream
    @Synchronized fun write(bytes: ByteArray) { shell.outputStream.write(bytes); shell.outputStream.flush() }
    fun resize(cols: Int, rows: Int) = window.changeWindowDimensions(cols.coerceAtLeast(2), rows.coerceAtLeast(2), 0, 0)
    override fun close() { try { shell.close() } finally { session.close() } }
}
