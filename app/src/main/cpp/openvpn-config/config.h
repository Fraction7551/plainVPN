/*
 * Hand-written config.h for building OpenVPN 2.7 against Android's Bionic libc.
 * Mirrors what config.h.cmake.in would produce for CMAKE_SYSTEM_NAME=Android
 * with mbed TLS, LZ4 and LZO enabled.
 */
#ifndef OVPNTV_CONFIG_H
#define OVPNTV_CONFIG_H

#define CONFIGURE_DEFINES "android-tv mbedtls lz4 lzo"

#define TARGET_ANDROID 1
#define TARGET_ALIAS "Android"
#define ENABLE_ASYNC_PUSH 1
#define ENABLE_SITNL 1
#define ENABLE_PORT_SHARE 1

#define ENABLE_CRYPTO_MBEDTLS 1
#define HAVE_PSA_CRYPTO_H 1

#define ENABLE_DEBUG 1
#define ENABLE_FRAGMENT 1
#define ENABLE_LZ4 1
#define ENABLE_LZO 1
#define HAVE_LZO_LZO1X_H 1
#define ENABLE_NTLM 1
#define ENABLE_MANAGEMENT 1
#define ENABLE_OFB_CFB_MODE
#define ENABLE_PLUGIN 1

#define HAVE_ARPA_INET_H 1
#define HAVE_BASENAME 1
#define HAVE_CHDIR 1
#define HAVE_CHROOT 1
#define HAVE_CMSGHDR 1
#define HAVE_DAEMON 1
#define HAVE_DIRNAME 1
#define HAVE_DLFCN_H 1
#define HAVE_DUP 1
#define HAVE_DUP2 1
#define HAVE_EPOLL_CREATE 1
#define HAVE_ERR_H 1
#define HAVE_FCNTL_H 1
#define HAVE_FORK 1
#define HAVE_EXECVE 1
#define HAVE_FTRUNCATE 1
#define HAVE_GETGRNAM 1
#define HAVE_GETPWNAM 1
#define HAVE_GETRLIMIT 1
#define HAVE_GETSOCKNAME 1
#define HAVE_GETTIMEOFDAY 1
#define HAVE_GRP_H 1
#define HAVE_IN_PKTINFO 1
#define HAVE_IPI_SPEC_DST 1
#define HAVE_LIBGEN_H 1
#define HAVE_LIMITS_H 1
#define HAVE_MLOCKALL 1
#define HAVE_MSGHDR 1
#define HAVE_NETDB_H 1
#define HAVE_NETINET_IN_H 1
#define HAVE_NETINET_IP_H 1
#define HAVE_NET_IF_H 1
#define HAVE_NICE 1
#define HAVE_OPENLOG 1
#define HAVE_POLL_H 1
#define HAVE_PWD_H 1
#define HAVE_RECVMSG 1
#define HAVE_SENDMSG 1
#define HAVE_RESOLV_H 1
#define HAVE_SA_FAMILY_T 1
#define HAVE_SETGID 1
#define HAVE_SETSID 1
#define HAVE_SETSOCKOPT 1
#define HAVE_SETUID 1
#define HAVE_STAT 1
#define HAVE_STDARG_H 1
#define HAVE_STDINT_H 1
#define HAVE_STDIO_H 1
#define HAVE_STDLIB_H 1
#define HAVE_STRINGS_H 1
#define HAVE_STRING_H 1
#define HAVE_SYSLOG 1
#define HAVE_SYSLOG_H 1
#define HAVE_SYS_EPOLL_H 1
#define HAVE_SYS_INOTIFY_H 1
#define HAVE_SYS_IOCTL_H 1
#define HAVE_SYS_MMAN_H 1
#define HAVE_SYS_SOCKET_H 1
#define HAVE_SYS_STAT_H 1
#define HAVE_SYS_TIME_H 1
#define HAVE_SYS_UIO_H 1
#define HAVE_SYS_UN_H 1
#define HAVE_SYS_WAIT_H 1
#define HAVE_UNISTD_H 1

#define IFCONFIG_PATH "/system/bin/ifconfig"
#define IPROUTE_PATH "/system/bin/ip"
#define ROUTE_PATH "/system/bin/route"

#define OPENVPN_VERSION_RESOURCE 2,7,8,0
#define PACKAGE "openvpn"
#define PACKAGE_NAME "OpenVPN"
#define PACKAGE_STRING "OpenVPN 2.7.8"
#define PACKAGE_VERSION "2.7.8"
#define PACKAGE_TARNAME "openvpn"

#define TAP_WIN_COMPONENT_ID "tap0901"
#define TAP_WIN_MIN_MAJOR 9
#define TAP_WIN_MIN_MINOR 9

#ifndef _GNU_SOURCE
#define _GNU_SOURCE 1
#endif

#endif /* OVPNTV_CONFIG_H */
