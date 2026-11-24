package dev.remotegpt.client

import kotlinx.coroutines.*
import net.schmizz.sshj.connection.channel.direct.Session
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.io.InputStream
import java.io.OutputStream
import java.net.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory

/** The official proxy is a byte tunnel to a WebSocket, not a JSONL transport. */
class ProxyTransport(private val ssh: SshConnection) : MessageTransport {
    private var socket: WebSocketClient? = null
    private var command: Session.Command? = null
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val disposed = AtomicBoolean(false)
    override val isOpen: Boolean get() = !disposed.get() && socket?.isOpen == true

    override suspend fun connect(onMessage: (String) -> Unit, onClosed: (Throwable?) -> Unit) = withContext(Dispatchers.IO) {
        val channel = ssh.proxy().also { command = it }
        // Always drain stderr separately. It must never enter the JSON/WebSocket parser.
        ioScope.launch { runCatching { val bytes = ByteArray(4096); while (channel.errorStream.read(bytes) >= 0) Unit } }
        val stream = StreamSocket(channel.inputStream, channel.outputStream) { channel.close() }
        val ws = object : WebSocketClient(URI("ws://localhost/")) {
            override fun onOpen(handshakedata: ServerHandshake?) = Unit
            override fun onMessage(message: String) = onMessage.invoke(message)
            override fun onClose(code: Int, reason: String?, remote: Boolean) = onClosed(ProxyClosed(code, remote,
                reason?.contains("pong", ignoreCase = true) == true))
            override fun onError(ex: Exception?) = onClosed(ex)
        }
        ws.setSocketFactory(object : SocketFactory() {
            override fun createSocket(): Socket = stream
            override fun createSocket(host: String?, port: Int): Socket = stream
            override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = stream
            override fun createSocket(host: InetAddress?, port: Int): Socket = stream
            override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket = stream
        })
        ws.connectionLostTimeout = 30
        socket = ws
        if (!ws.connectBlocking(20, TimeUnit.SECONDS)) {
            close()
            throw ClientStateException(ClientErrorCode.PROXY_CONNECT_FAILED, "Could not connect to Codex App Server proxy")
        }
    }

    override fun send(text: String) {
        if (!isOpen) throw ClientStateException(ClientErrorCode.CONNECTION_CLOSED, "Connection is closed")
        socket!!.send(text)
    }
    override fun close() {
        if (!disposed.compareAndSet(false, true)) return
        runCatching { socket?.close() }; runCatching { command?.close() }; ioScope.cancel()
    }
}

/** An already-connected SSH channel presented to a standard WebSocket implementation. */
internal class StreamSocket(private val source: InputStream, private val sink: OutputStream, private val dispose: () -> Unit) : Socket() {
    @Volatile private var ended = false
    private var readTimeout = 0
    override fun getInputStream() = source
    override fun getOutputStream() = sink
    override fun isConnected() = !ended
    override fun isClosed() = ended
    override fun isBound() = true
    override fun connect(endpoint: SocketAddress?, timeout: Int) = Unit
    override fun connect(endpoint: SocketAddress?) = Unit
    override fun setTcpNoDelay(on: Boolean) = Unit
    override fun getTcpNoDelay() = true
    override fun setKeepAlive(on: Boolean) = Unit
    override fun setSoTimeout(timeout: Int) { readTimeout = timeout }
    override fun getSoTimeout() = readTimeout
    override fun getInetAddress(): InetAddress = InetAddress.getLoopbackAddress()
    override fun getLocalAddress(): InetAddress = InetAddress.getLoopbackAddress()
    override fun getPort() = 80
    override fun getLocalPort() = 0
    override fun getRemoteSocketAddress(): SocketAddress = InetSocketAddress(inetAddress, port)
    override fun close() { if (!ended) { ended = true; dispose() } }
}
