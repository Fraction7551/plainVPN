# plainVPN

An Android TV VPN client. It runs OpenVPN® 2.7.8 (built from `../openvpn-src`) with its own TV interface, and adds a scheduled disconnect and reconnect plus per-app split tunnelling. The look comes from the Plain AI Style Kit. Asset generators are in `design-tools/`.

OpenVPN® is a registered trademark of OpenVPN Inc. plainVPN is not affiliated with OpenVPN Inc.

## Layout

```
plainVPN/
  openvpn-src/openvpn-2.7.8     upstream OpenVPN source (unmodified)
  deps/                         mbedtls-3.6.4, lz4-1.10.0, lzo-2.10 (unmodified)
  toolchain/                    JDK 21 used for the build
  plainvpn/                     this app (package id dev.ovpntv)
    app/src/main/cpp/           CMake build of OpenVPN + deps, Android config.h
    app/src/main/java/dev/ovpntv/
      vpn/        OpenVpnService (VpnService + management-interface bridge), TunConfig, ConfigSanitizer
      schedule/   ReconnectScheduler (alarm-clock alarms), receivers for alarms/boot/clock changes
      importer/   LAN upload server for .ovpn files
      update/     self-updater (GitHub releases)
      ui/         Compose UI: theme, components, Home/Profiles/Schedule/Split tunnelling/Settings/Logs
    design-tools/ generators for the vector drawables and the TV banner
```

## Build

```powershell
$env:JAVA_HOME = "<projects>\plainVPN\toolchain\jdk-21.0.12.1+1"
cd <projects>\plainVPN\plainvpn
.\gradlew.bat assembleRelease     # app\build\outputs\apk\release\app-release.apk
```

Release builds are signed with the key named in `keystore.properties` (not in git). Without that file they fall back to the debug key.

## Install on a TV

Turn on Developer options and USB/network debugging on the TV, then run:

```
adb connect <tv-ip>:5555
adb install -r app\build\outputs\apk\release\app-release.apk
```

## Importing profiles

* **Wi-Fi:** Profiles → "From phone or computer". Open the URL shown on the TV in a browser and enter the PIN.
* **USB/storage:** uses the system file picker if the TV has one.
* **Drop folder:** `adb push my.ovpn /sdcard/Android/data/dev.ovpntv/files/`, then Profiles → "Scan drop folder".

## How it works

OpenVPN is compiled as a PIE executable named `libovpnexec.so`, so that Android extracts it to the native-library directory, the only place an app is allowed to exec from. The service starts it with `--management <socket> unix --management-hold --management-query-passwords --persist-tun`. It then answers OpenVPN's Android requests (`IFCONFIG`, `ROUTE`, `DNSSERVER`, `PROTECTFD`, `OPENTUN`, ...) as described in `openvpn-2.7.8/doc/android.txt`. It passes the tun fd and protected sockets over the UNIX socket as SCM_RIGHTS.

## Limitations

* `dev tap` profiles are rejected, because Android's VPN API is layer 3 only.
* mbed TLS has no PKCS#12 support, so `pkcs12` profiles fail. Password-protected private keys and static-challenge (OTP) logins are not handled yet.
* While the schedule has the VPN down, traffic goes out unprotected unless "Block VPN apps while reconnecting" is on. For a full kill switch, turn on Android's "Always-on VPN" and "Block connections without VPN" where the TV supports them.
