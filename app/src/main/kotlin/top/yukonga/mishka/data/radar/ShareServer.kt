package top.yukonga.mishka.data.radar

import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 临时 HTTP 分享服务：把「选中目标里通过的节点」渲染成的订阅文本挂到局域网 URL，
 * 对方扫码/复制链接后由客户端自行拉取。
 *
 * 生命周期：start() 起后台线程监听；stop() 关 Socket 让 accept 抛异常退出。
 * 页面关掉 = 服务停 = 链接失效，正符合「分享那几分钟」的预期。
 */
class ShareServer(
    private val v2raynText: String,
    private val clashText: String,
) {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    @Volatile
    var port: Int = 0
        private set

    fun start() {
        if (!running.compareAndSet(false, true)) return
        // 固定五位数端口：换设备只需加一次订阅，以后直接更新就能拿到最新节点。
        // 占用时顺延下一个，绝大多数情况落在 38231。
        for (candidate in PORTS) {
            try {
                val ss = ServerSocket(candidate)
                ss.reuseAddress = true
                serverSocket = ss
                port = ss.localPort
                acceptThread = thread(name = "radar-share", isDaemon = true) { acceptLoop(ss) }
                return
            } catch (_: IOException) {
            }
        }
        running.set(false)
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get()) {
            val client = try { ss.accept() } catch (e: SocketException) { break }
            thread(name = "radar-share-conn", isDaemon = true) { handle(client) }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.use {
                val req = socket.getInputStream().bufferedReader().readLine() ?: return
                val path = req.split(' ').getOrNull(1) ?: "/"
                val body = when {
                    path.startsWith("/radar") -> v2raynText
                    path.startsWith("/clash") -> clashText
                    else -> null
                }
                if (body == null) {
                    respond(socket, 404, "not found")
                    return
                }
                respond(socket, 200, body)
            }
        } catch (_: IOException) {
        }
    }

    private fun respond(socket: Socket, code: Int, body: String) {
        val status = if (code == 200) "200 OK" else "404 Not Found"
        val bytes = body.toByteArray(Charsets.UTF_8)
        val out = socket.getOutputStream()
        out.write(
            ("HTTP/1.1 $status\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n" +
                "\r\n").toByteArray(Charsets.UTF_8)
        )
        out.write(bytes)
        out.flush()
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        serverSocket = null
    }

    /** 局域网 IPv4：优先 192.168 / 10.x / 172.16-31，取第一个非回环 IPv4 */
    fun lanIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map(InetAddress::getHostAddress)
            .firstOrNull { it.startsWith("192.168.") || it.startsWith("10.") || it.startsWith("172.") }
    }.getOrNull()

    /** 完整分享 URL（v2rayN / Clash 各一个出口） */
    fun url(format: String): String? {
        val ip = lanIp() ?: return null
        return when (format) {
            "v2rayN" -> "http://$ip:$port/radar"
            "Clash" -> "http://$ip:$port/clash.yaml"
            else -> null
        }
    }

    companion object {
        /** 固定端口候选：五位数，避开 8080 / 9099 / 7890 等常见端口；占用时顺延 */
        private val PORTS = intArrayOf(38231, 38232, 38233)
    }
}