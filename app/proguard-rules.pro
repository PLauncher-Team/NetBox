# Keep the LibXray bridge intact: it dispatches native Xray operations.
-keep class libXray.** { *; }

# Keep the native tun2socks service bridge and its entry points.
-keep class com.v2ray.ang.service.TProxyService { *; }

# Preserve names of classes exposing native methods for JNI lookups.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
