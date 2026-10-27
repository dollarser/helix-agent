# The bundled private Runtime's fd-relative cleanup entry is resolved by its JNI name.
-keepclasseswithmembers,allowoptimization class com.helix.runtime.proot.core.PtyStateNative {
    native <methods>;
}
