package com.example.netbox.xray

import org.json.JSONArray
import org.json.JSONObject

object XrayConfigBuilder {

    fun buildJsonConfig(
        config: VlessConfig,
        localSocksPort: Int = 10808,
        localHttpPort: Int = 10809
    ): String {
        val root = JSONObject()
        val log = JSONObject().apply {
            put("loglevel", "warning")
        }
        root.put("log", log)

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
                put("port", localSocksPort)
                put("listen", "127.0.0.1")
                put("protocol", "socks")
                put("settings", JSONObject().apply {
                    put("udp", true)
                    put("auth", "noauth")
                })
            })
            put(JSONObject().apply {
                put("tag", "http-in")
                put("port", localHttpPort)
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

        val vnextUser = JSONObject().apply {
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
            put("users", JSONArray().apply { put(vnextUser) })
        }

        val vlessOutbound = JSONObject().apply {
            put("tag", "proxy")
            put("protocol", "vless")
            put("settings", JSONObject().apply {
                put("vnext", JSONArray().apply { put(vnextItem) })
            })
            val network = config.params["type"] ?: "tcp"
            val security = config.params["security"] ?: "none"

            val streamSettings = JSONObject().apply {
                put("network", network)
                put("security", security)

                if (security == "reality") {
                    val realitySettings = JSONObject().apply {
                        put("publicKey", config.params["pbk"] ?: "")
                        put("fingerprint", config.params["fp"] ?: "chrome")
                        put("serverName", config.params["sni"] ?: "")
                        put("shortId", config.params["sid"] ?: "")
                        put("spiderX", config.params["spx"] ?: "")
                    }
                    put("realitySettings", realitySettings)
                } else if (security == "tls") {
                    val tlsSettings = JSONObject().apply {
                        put("serverName", config.params["sni"] ?: config.address)
                        put("fingerprint", config.params["fp"] ?: "chrome")
                        val allowInsecure = config.params["allowinsecure"] in setOf("1", "true")
                        put("allowInsecure", allowInsecure)
                    }
                    put("tlsSettings", tlsSettings)
                }

                when (network) {
                    "ws" -> {
                        val wsSettings = JSONObject().apply {
                            put("path", config.params["path"] ?: "/")
                            put("headers", JSONObject().apply {
                                put("Host", config.params["host"] ?: config.params["sni"] ?: "")
                            })
                        }
                        put("wsSettings", wsSettings)
                    }
                    "grpc" -> {
                        val grpcSettings = JSONObject().apply {
                            put("serviceName", config.params["servicename"] ?: "")
                            put("multiMode", config.params["mode"] == "multi")
                        }
                        put("grpcSettings", grpcSettings)
                    }
                    "xhttp" -> {
                        val xhttpSettings = JSONObject().apply {
                            put("path", config.params["path"] ?: "/")
                            val host = config.params["host"] ?: config.params["sni"]
                            if (!host.isNullOrBlank()) {
                                put("host", host)
                            }
                            put("mode", config.params["mode"] ?: "auto")
                        }
                        put("xhttpSettings", xhttpSettings)
                    }
                }
            }
            put("streamSettings", streamSettings)
        }

        val directOutbound = JSONObject().apply {
            put("tag", "direct")
            put("protocol", "freedom")
        }

        val blockOutbound = JSONObject().apply {
            put("tag", "block")
            put("protocol", "blackhole")
        }

        val dnsOutbound = JSONObject().apply {
            put("tag", "dns-out")
            put("protocol", "dns")
        }

        val outbounds = JSONArray().apply {
            put(vlessOutbound)
            put(directOutbound)
            put(blockOutbound)
            put(dnsOutbound)
        }
        root.put("outbounds", outbounds)

        val routing = JSONObject().apply {
            put("domainStrategy", "IPIfNonMatch")
            val rules = JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "field")
                    put("inboundTag", JSONArray().apply { put("dns-in") })
                    put("outboundTag", "dns-out")
                })
            }
            put("rules", rules)
        }
        root.put("routing", routing)

        return root.toString(2)
    }

    private fun String?.isNullOrEmptyOrNone(): Boolean {
        return this.isNullOrBlank() || this.equals("none", ignoreCase = true)
    }
}
