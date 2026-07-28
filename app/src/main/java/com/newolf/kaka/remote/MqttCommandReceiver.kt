package com.newolf.kaka.remote

import com.newolf.kaka.util.Logger
import kotlinx.coroutines.*
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

class MqttCommandReceiver(
    private val brokerUrl: String = "tcp://broker.emqx.io:1883",
    private val clientId: String = "auto_punch_${System.currentTimeMillis()}",
    private val username: String? = null,
    private val userPassword: String? = null,
    private val commandTopic: String = "punch/command",
    private val onCommand: (type: String?) -> Unit,
) {
    companion object {
        private const val TAG = "MQTT"
    }

    private var mqttClient: MqttClient? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun connect() {
        scope.launch {
            try {
                Logger.i(TAG, "connect: 尝试连接 broker=$brokerUrl clientId=$clientId")
                mqttClient = MqttClient(brokerUrl, clientId, MemoryPersistence())
                val options = MqttConnectOptions().apply {
                    isCleanSession = true
                    connectionTimeout = 10
                    keepAliveInterval = 60
                    if (username != null) {
                        userName = username
                        password = userPassword?.toCharArray()
                    }
                }
                mqttClient?.connect(options)
                mqttClient?.subscribe(commandTopic, 1) { _, message ->
                    val payload = String(message.payload)
                    Logger.i(TAG, "收到指令: $payload")
                    val type = parseJsonField(payload, "type")
                    val token = parseJsonField(payload, "token")
                    if (token == "your_mqtt_token_here") {
                        onCommand(type)
                    } else {
                        Logger.w(TAG, "指令 token 校验失败，忽略 payload=$payload")
                    }
                }
                Logger.i(TAG, "已连接 broker=$brokerUrl，订阅 topic=$commandTopic")
            } catch (e: CancellationException) {
                Logger.w(TAG, "connect 被取消")
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "连接失败: ${e.message}，10s 后重试", e)
                delay(10_000)
                connect()
            }
        }
    }

    fun disconnect() {
        Logger.i(TAG, "disconnect")
        try {
            mqttClient?.disconnect()
        } catch (t: Throwable) {
            Logger.w(TAG, "disconnect 异常", t)
        }
        scope.cancel()
    }

    private fun parseJsonField(json: String, key: String): String? {
        val regex = """"$key"\s*:\s*"([^"]*)"""".toRegex()
        return regex.find(json)?.groupValues?.get(1)
    }
}