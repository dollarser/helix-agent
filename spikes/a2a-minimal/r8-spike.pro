# HXA-077 standalone fallback entry points. This is not a production app rule set.
-keep public class com.helix.spikes.a2a.minimal.OkHttpA2aClientFacade { public *; }
-keep public interface com.helix.spikes.a2a.minimal.A2aClientFacade { public *; }
-keep public interface com.helix.spikes.a2a.minimal.A2aStreamListener { public *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# OkHttp probes these optional JVM security providers by class name. Android uses its platform
# TLS provider, and Helix does not package any of these optional implementations.
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
