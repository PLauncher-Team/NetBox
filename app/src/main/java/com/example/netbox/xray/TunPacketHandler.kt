package com.example.netbox.xray

import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

class TunPacketHandler(
    private val pfd: ParcelFileDescriptor,
    private val vlessConfig: VlessConfig
) {
    @Volatile
    private var isRunning = true
    private val activeTcpSockets = ConcurrentHashMap<String, Socket>()

    fun start() {
        thread(name = "NetBox-TunReader") {
            val inputStream = FileInputStream(pfd.fileDescriptor)
            val outputStream = FileOutputStream(pfd.fileDescriptor)
            val buffer = ByteArray(32768)

            while (isRunning) {
                try {
                    val length = inputStream.read(buffer)
                    if (length <= 0) continue

                    // Parse IP Header
                    val version = (buffer[0].toInt() and 0xFF) shr 4
                    if (version != 4) continue // IPv4 only for now

                    val headerLen = (buffer[0].toInt() and 0x0F) * 4
                    val protocol = buffer[9].toInt() and 0xFF

                    // Extract IPs
                    val srcIp = InetAddress.getByAddress(buffer.copyOfRange(12, 16))
                    val dstIp = InetAddress.getByAddress(buffer.copyOfRange(16, 20))

                    if (protocol == 17) { // UDP (DNS / port 53)
                        val udpHeaderOffset = headerLen
                        val srcPort = ((buffer[udpHeaderOffset].toInt() and 0xFF) shl 8) or (buffer[udpHeaderOffset + 1].toInt() and 0xFF)
                        val dstPort = ((buffer[udpHeaderOffset + 2].toInt() and 0xFF) shl 8) or (buffer[udpHeaderOffset + 3].toInt() and 0xFF)

                        if (dstPort == 53) {
                            val payloadOffset = udpHeaderOffset + 8
                            val payloadLen = length - payloadOffset
                            if (payloadLen > 0) {
                                val dnsQuery = buffer.copyOfRange(payloadOffset, payloadOffset + payloadLen)
                                handleDnsAsync(dnsQuery) { dnsResp ->
                                    val respPacket = buildUdpPacket(dstIp, srcIp, 53, srcPort, dnsResp)
                                    synchronized(outputStream) {
                                        try {
                                            outputStream.write(respPacket)
                                            outputStream.flush()
                                        } catch (_: Exception) {}
                                    }
                                }
                            }
                        }
                    } else if (protocol == 6) { // TCP
                        val tcpHeaderOffset = headerLen
                        val srcPort = ((buffer[tcpHeaderOffset].toInt() and 0xFF) shl 8) or (buffer[tcpHeaderOffset + 1].toInt() and 0xFF)
                        val dstPort = ((buffer[tcpHeaderOffset + 2].toInt() and 0xFF) shl 8) or (buffer[tcpHeaderOffset + 3].toInt() and 0xFF)
                        val flags = buffer[tcpHeaderOffset + 13].toInt() and 0xFF

                        val isSyn = (flags and 0x02) != 0
                        val isFin = (flags and 0x01) != 0
                        val isRst = (flags and 0x04) != 0

                        val key = "${srcIp.hostAddress}:$srcPort->${dstIp.hostAddress}:$dstPort"

                        if (isSyn && !isFin && !isRst) {
                            thread(name = "NetBox-TCP-$srcPort") {
                                handleTcpConnection(dstIp.hostAddress ?: "", dstPort)
                            }
                        } else if (isFin || isRst) {
                            activeTcpSockets.remove(key)?.close()
                        }
                    }
                } catch (_: Exception) {
                    if (!isRunning) break
                }
            }
        }
    }

    fun stop() {
        isRunning = false
        activeTcpSockets.values.forEach {
            try { it.close() } catch (_: Exception) {}
        }
        activeTcpSockets.clear()
    }

    private fun handleTcpConnection(targetHost: String, targetPort: Int) {
        try {
            val socket = VlessClient.connect(vlessConfig, targetHost, targetPort)
            val key = "$targetHost:$targetPort"
            activeTcpSockets[key] = socket
        } catch (_: Exception) {}
    }

    private fun handleDnsAsync(dnsQuery: ByteArray, onResponse: (ByteArray) -> Unit) {
        thread {
            try {
                val socket = DatagramSocket()
                XrayVpnService.instance?.protect(socket) // Защита DNS сокета от зацикливания
                socket.soTimeout = 3000
                val dnsServer = InetAddress.getByName("1.1.1.1")
                val sendPacket = DatagramPacket(dnsQuery, dnsQuery.size, dnsServer, 53)
                socket.send(sendPacket)

                val recvBuf = ByteArray(2048)
                val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
                socket.receive(recvPacket)

                val responseData = recvBuf.copyOf(recvPacket.length)
                socket.close()
                onResponse(responseData)
            } catch (_: Exception) {}
        }
    }

    private fun buildUdpPacket(
        srcIp: InetAddress,
        dstIp: InetAddress,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val totalLen = 20 + 8 + payload.size
        val packet = ByteArray(totalLen)

        // IPv4 Header
        packet[0] = 0x45.toByte() // Ver 4, IHL 5
        packet[1] = 0x00.toByte() // TOS
        packet[2] = ((totalLen shr 8) and 0xFF).toByte()
        packet[3] = (totalLen and 0xFF).toByte()
        packet[4] = 0x00.toByte() // ID
        packet[5] = 0x00.toByte()
        packet[6] = 0x40.toByte() // Flags: Don't Fragment
        packet[7] = 0x00.toByte()
        packet[8] = 64.toByte()   // TTL
        packet[9] = 17.toByte()   // Protocol UDP
        packet[10] = 0x00.toByte() // Checksum
        packet[11] = 0x00.toByte()

        System.arraycopy(srcIp.address, 0, packet, 12, 4)
        System.arraycopy(dstIp.address, 0, packet, 16, 4)

        // Calculate IPv4 Header Checksum
        val ipChecksum = computeChecksum(packet, 0, 20)
        packet[10] = ((ipChecksum shr 8) and 0xFF).toByte()
        packet[11] = (ipChecksum and 0xFF).toByte()

        // UDP Header
        val udpLen = 8 + payload.size
        packet[20] = ((srcPort shr 8) and 0xFF).toByte()
        packet[21] = (srcPort and 0xFF).toByte()
        packet[22] = ((dstPort shr 8) and 0xFF).toByte()
        packet[23] = (dstPort and 0xFF).toByte()
        packet[24] = ((udpLen shr 8) and 0xFF).toByte()
        packet[25] = (udpLen and 0xFF).toByte()
        packet[26] = 0x00.toByte() // UDP Checksum (optional)
        packet[27] = 0x00.toByte()

        System.arraycopy(payload, 0, packet, 28, payload.size)
        return packet
    }

    private fun computeChecksum(buf: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + length) {
            val high = (buf[i].toInt() and 0xFF) shl 8
            val low = if (i + 1 < offset + length) buf[i + 1].toInt() and 0xFF else 0
            sum += (high or low)
            i += 2
        }
        while (sum shr 16 > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv() and 0xFFFF
    }
}
