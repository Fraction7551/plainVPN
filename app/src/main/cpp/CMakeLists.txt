# Builds the OpenVPN 2.x client as an Android executable.
#
# The binary is named libovpnexec.so so that the package manager extracts it
# into the app's nativeLibraryDir, the only app-owned location from which
# Android 10+ still allows exec(). The Kotlin side launches it as a child
# process and drives it through the management interface (see doc/android.txt
# in the OpenVPN sources).
cmake_minimum_required(VERSION 3.22)
project(ovpntv C)

set(CMAKE_C_STANDARD 11)

# Third-party sources live next to the project, in the test folder.
get_filename_component(TEST_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../.." ABSOLUTE)
set(OPENVPN_DIR "${TEST_ROOT}/openvpn-src/openvpn-2.7.8")
set(MBEDTLS_DIR "${TEST_ROOT}/deps/mbedtls-3.6.7")
set(LZ4_DIR "${TEST_ROOT}/deps/lz4-1.10.0")
set(LZO_DIR "${TEST_ROOT}/deps/lzo-2.10")

foreach(dir OPENVPN_DIR MBEDTLS_DIR LZ4_DIR LZO_DIR)
    if(NOT EXISTS "${${dir}}")
        message(FATAL_ERROR "${dir} not found at ${${dir}}")
    endif()
endforeach()

# __FILE__ ends up in the binary (OpenVPN's and Mbed TLS's error messages); keep the
# build machine's folders (user name included) out of the APK.
add_compile_options("-ffile-prefix-map=${TEST_ROOT}/=" "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}/=")

# ---- mbed TLS -------------------------------------------------------------
set(ENABLE_PROGRAMS OFF CACHE BOOL "" FORCE)
set(ENABLE_TESTING OFF CACHE BOOL "" FORCE)
set(MBEDTLS_FATAL_WARNINGS OFF CACHE BOOL "" FORCE)
set(USE_SHARED_MBEDTLS_LIBRARY OFF CACHE BOOL "" FORCE)
set(USE_STATIC_MBEDTLS_LIBRARY ON CACHE BOOL "" FORCE)
add_subdirectory("${MBEDTLS_DIR}" mbedtls EXCLUDE_FROM_ALL)

# ---- LZ4 ------------------------------------------------------------------
add_library(lz4 STATIC "${LZ4_DIR}/lib/lz4.c")
target_include_directories(lz4 PUBLIC "${LZ4_DIR}/lib")

# ---- LZO ------------------------------------------------------------------
file(GLOB LZO_SOURCES "${LZO_DIR}/src/*.c")
add_library(lzo2 STATIC ${LZO_SOURCES})
target_include_directories(lzo2 PUBLIC "${LZO_DIR}/include" PRIVATE "${LZO_DIR}")

# ---- OpenVPN --------------------------------------------------------------
set(OVPN_SRC "${OPENVPN_DIR}/src/openvpn")
set(OPENVPN_SOURCES
    ${OPENVPN_DIR}/src/compat/compat-basename.c
    ${OPENVPN_DIR}/src/compat/compat-daemon.c
    ${OPENVPN_DIR}/src/compat/compat-dirname.c
    ${OPENVPN_DIR}/src/compat/compat-gettimeofday.c
    ${OPENVPN_DIR}/src/compat/compat-strsep.c
)
foreach(f
        argv auth_token base64 buffer clinat comp comp-lz4 compstub console
        console_builtin crypto crypto_epoch crypto_mbedtls crypto_mbedtls_legacy
        dco dhcp dns env_set error event fdmisc forward fragment gremlin helper
        httpdigest init interval list lladdr lzo manage mbuf misc mroute mss mtcp
        mtu mudp multi multi_io ntlm occ openvpn options options_parse
        options_util otime packet_id ping pkcs11 pkcs11_mbedtls platform plugin
        pool proto proxy ps push push_util reflect_filter reliable route
        run_command schedule session_id shaper sig socket socket_util socks ssl
        ssl_mbedtls ssl_ncp ssl_pkt ssl_util ssl_verify ssl_verify_mbedtls status
        tls_crypt tun tun_afunix networking_sitnl vlan)
    if(EXISTS "${OVPN_SRC}/${f}.c")
        list(APPEND OPENVPN_SOURCES "${OVPN_SRC}/${f}.c")
    endif()
endforeach()

set(OPENVPN_VERSION_MAJOR 2)
set(OPENVPN_VERSION_MINOR 7)
set(OPENVPN_VERSION_PATCH .8)
configure_file("${OPENVPN_DIR}/include/openvpn-plugin.h.in"
    "${CMAKE_CURRENT_BINARY_DIR}/generated/openvpn-plugin.h" @ONLY)

add_executable(ovpnexec ${OPENVPN_SOURCES})
set_target_properties(ovpnexec PROPERTIES OUTPUT_NAME "libovpnexec.so" PREFIX "" SUFFIX "")
target_include_directories(ovpnexec PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/openvpn-config"
    "${CMAKE_CURRENT_BINARY_DIR}/generated"
    "${OPENVPN_DIR}"
    "${OPENVPN_DIR}/include"
    "${OPENVPN_DIR}/src/compat")
target_compile_definitions(ovpnexec PRIVATE
    HAVE_CONFIG_H
    DEFAULT_DNS_UPDOWN="/dev/null"
    PLUGIN_LIBDIR="/dev/null")
target_compile_options(ovpnexec PRIVATE
    -Wno-unused-parameter -Wno-sign-compare -Wno-deprecated-declarations)
target_link_libraries(ovpnexec PRIVATE mbedtls mbedx509 mbedcrypto lz4 lzo2 log dl)
# Android requires position independent executables.
target_link_options(ovpnexec PRIVATE -pie)
