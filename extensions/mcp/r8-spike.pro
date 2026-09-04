# HXA-070 standalone shrinker entry points. This is a Spike input, not a production app rule set.
-keep public class com.helix.extensions.mcp.McpClients { public *; }
-keep public interface com.helix.extensions.mcp.McpClientFacade { public *; }
-keep public interface com.helix.extensions.mcp.McpClientSession { public *; }
-keep public class com.helix.extensions.mcp.McpServerIdentity { public *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# OkHttp probes these optional JVM security providers by class name. Helix does not package
# them; Android uses its platform TLS provider. Keep every other missing class as a hard error.
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# kotlin-logging bundles optional Logback adapters in the JVM artifact. Android uses the SLF4J
# fallback path and does not package Logback Classic.
-dontwarn ch.qos.logback.classic.**
