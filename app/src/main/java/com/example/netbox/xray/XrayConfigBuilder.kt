package com.example.netbox.xray

import org.json.JSONArray
import org.json.JSONObject

object XrayConfigBuilder {

    fun buildJsonConfig(
        config: VlessConfig,
        proxyPool: List<VlessConfig> = listOf(config),
        probeUrl: String = "https://one.one.one.one",
        observatoryIntervalSeconds: Int = 30,
        probeTimeoutSeconds: Int = 1
    ): String {
        val configs = (proxyPool.ifEmpty { listOf(config) }).distinctBy { it.rawUri }
        val root = JSONObject()
        root.put("log", JSONObject().put("loglevel", "warning"))

        val dns = JSONObject().apply {
            put("servers", JSONArray().apply {
                put("1.1.1.1")
                put("8.8.8.8")
                put("1.0.0.1")
                put("https://dns.google/dns-query")
                put("localhost")
            })
            put("queryStrategy", "UseIP")
        }
        root.put("dns", dns)

        val inbounds = JSONArray().apply {
            put(JSONObject().apply {
                put("tag", "socks-in")
                put("port", 10808)
                put("listen", "127.0.0.1")
                put("protocol", "socks")
                put("settings", JSONObject().apply {
                    put("udp", true)
                    put("auth", "noauth")
                })
            })
            put(JSONObject().apply {
                put("tag", "http-in")
                put("port", 10809)
                put("listen", "127.0.0.1")
                put("protocol", "http")
            })
            put(JSONObject().apply {
                put("tag", "dns-in")
                put("port", 10853)
                put("listen", "127.0.0.1")
                put("protocol", "dokodemo-door")
                put("settings", JSONObject().apply {
                    put("address", "1.1.1.1")
                    put("port", 53)
                    put("network", "tcp,udp")
                })
            })
        }
        root.put("inbounds", inbounds)

        val outbounds = JSONArray()
        configs.forEachIndexed { index, proxyConfig ->
            outbounds.put(buildProxyOutbound(proxyConfig, "proxy-$index"))
        }
        outbounds.put(JSONObject().apply {
            put("tag", "direct")
            put("protocol", "freedom")
        })
        outbounds.put(JSONObject().apply {
            put("tag", "block")
            put("protocol", "blackhole")
        })
        outbounds.put(JSONObject().apply {
            put("tag", "dns-out")
            put("protocol", "dns")
        })
        root.put("outbounds", outbounds)

        // Keep the active candidate set limited to transports already supported by this app.
        // Burst Observatory applies the user's latency timeout, and leastPing automatically
        // sends new connections through the fastest proxy that is still responding.
        root.put("burstObservatory", JSONObject().apply {
            put("subjectSelector", JSONArray().put("proxy-"))
            put("pingConfig", JSONObject().apply {
                put("destination", probeUrl.ifBlank { "https://one.one.one.one" })
                put("connectivity", "")
                put("interval", "${observatoryIntervalSeconds.coerceAtLeast(10)}s")
                put("sampling", 1)
                put("timeout", "${probeTimeoutSeconds.coerceAtLeast(1)}s")
                put("httpMethod", "GET")
            })
        })

        val routing = JSONObject().apply {
            put("domainStrategy", "IPIfNonMatch")
            put("balancers", JSONArray().put(
                JSONObject().apply {
                    put("tag", "auto")
                    put("selector", JSONArray().put("proxy-"))
                    put("fallbackTag", "proxy-0")
                    put("strategy", JSONObject().put("type", "leastPing"))
                }
            ))
            put("rules", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "field")
                    put("inboundTag", JSONArray().put("dns-in"))
                    put("outboundTag", "dns-out")
                })
                put(JSONObject().apply {
                    put("type", "field")
                    put("network", "tcp,udp")
                    put("balancerTag", "auto")
                })
            })
        }
        root.put("routing", routing)

        return root.toString(2)
    }

    private fun buildProxyOutbound(config: VlessConfig, tag: String): JSONObject {
        val user = JSONObject().apply {
            put("id", config.uuid)
            put("encryption", config.params["encryption"] ?: "none")
            val flow = config.params["flow"]
            if (!flow.isNullOrEmptyOrNone()) {
                put("flow", flow)
            }
        }

        val vnextItem = JSONObject().apply {
            put("address", config.address)
            put("port", config.port)
            put("users", JSONArray().put(user))
        }

        val method = ProxyTransport.fromUriValue(config.params["type"])?.key ?: "raw"
        val security = config.params["security"] ?: "none"
        val streamSettings = JSONObject().apply {
            put("network", method)
            put("security", security)

            if (security == "reality") {
                put("realitySettings", JSONObject().apply {
                    put("publicKey", config.params["pbk"] ?: "")
                    put("fingerprint", config.params["fp"] ?: "chrome")
                    put("serverName", config.params["sni"] ?: "")
                    put("shortId", config.params["sid"] ?: "")
                    put("spiderX", config.params["spx"] ?: "")
                })
            } else if (security == "tls") {
                put("tlsSettings", JSONObject().apply {
                    put("serverName", config.params["sni"] ?: config.address)
                    put("fingerprint", config.params["fp"] ?: "chrome")
                    put("allowInsecure", config.params["allowinsecure"] in setOf("1", "true"))
                })
            }

            when (method) {
                "websocket" -> put("wsSettings", JSONObject().apply {
                    put("path", config.params["path"] ?: "/")
                    val host = config.params["host"] ?: config.params["sni"]
                    if (!host.isNullOrBlank()) {
                        put("headers", JSONObject().put("Host", host))
                    }
                })
                "grpc" -> put("grpcSettings", JSONObject().apply {
                    put("serviceName", config.params["servicename"] ?: "")
                    put("multiMode", config.params["mode"] == "multi")
                })
                "xhttp" -> put("xhttpSettings", JSONObject().apply {
                    put("path", config.params["path"] ?: "/")
                    val host = config.params["host"] ?: config.params["sni"]
                    if (!host.isNullOrBlank()) put("host", host)
                    put("mode", config.params["mode"] ?: "auto")
                })
                "httpupgrade" -> put("httpupgradeSettings", JSONObject().apply {
                    put("path", config.params["path"] ?: "/")
                    val host = config.params["host"] ?: config.params["sni"]
                    if (!host.isNullOrBlank()) put("host", host)
                })
                "mkcp" -> put("kcpSettings", JSONObject().apply {
                    val seed = config.params["seed"]
                    if (!seed.isNullOrBlank()) put("seed", seed)
                    put("header", JSONObject().put("type", config.params["headertype"] ?: "none"))
                })
                "raw" -> {
                    val headerType = config.params["headertype"] ?: config.params["headerType"] ?: "none"
                    if (!headerType.equals("none", ignoreCase = true) && headerType.isNotBlank()) {
                        put("rawSettings", JSONObject().put(
                            "header",
                            JSONObject().put("type", headerType)
                        ))
                    }
                }
            }
        }

        return JSONObject().apply {
            put("tag", tag)
            put("protocol", "vless")
            put("settings", JSONObject().put("vnext", JSONArray().put(vnextItem)))
            put("streamSettings", streamSettings)
        }
    }

    private fun String?.isNullOrEmptyOrNone(): Boolean =
        this.isNullOrBlank() || this.equals("none", ignoreCase = true)
}
