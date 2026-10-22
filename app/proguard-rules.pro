# WireGuardEngine calls wireguard-go's JNI functions on GoBackend by name (reflection),
# so R8 must keep them and their names. The JNI symbols themselves are bound to this class.
-keep class com.wireguard.android.backend.GoBackend {
    native <methods>;
}
