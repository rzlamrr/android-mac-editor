# MAC Editor for Android

[![Stars](https://img.shields.io/github/stars/jqssun/android-mac-editor)](https://github.com/jqssun/android-mac-editor/stargazers)
[![LSPosed](https://img.shields.io/github/downloads/Xposed-Modules-Repo/io.github.jqssun.maceditor/total?label=LSPosed&logo=Android&style=flat&labelColor=F48FB1&logoColor=ffffff)](https://github.com/Xposed-Modules-Repo/io.github.jqssun.maceditor/releases)
[![GitHub](https://img.shields.io/github/downloads/jqssun/android-mac-editor/total?label=GitHub&logo=GitHub)](https://github.com/jqssun/android-mac-editor/releases)
[![release](https://img.shields.io/github/v/release/jqssun/android-mac-editor)](https://github.com/jqssun/android-mac-editor/releases)
[![build](https://img.shields.io/github/actions/workflow/status/jqssun/android-mac-editor/apk.yml)](https://github.com/jqssun/android-mac-editor/actions/workflows/apk.yml)
[![license](https://img.shields.io/github/license/jqssun/android-mac-editor?color=green)](https://github.com/jqssun/android-mac-editor/blob/master/LICENSE)

A free and open-source module that gives you granular control over the Wi-Fi MAC address on Android devices. It supports manual MAC override and enables native MAC randomization support exposed by Android on supported hardware regardless of the OEM's implementation. You can use it, for example, to customize MAC behavior for privacy, or to assist devices with limited captive portal support to access the Internet on certain Wi-Fi networks.

## Compatibility

- Android 12+ (tested up to Android 16 QPR2)
- Rooted devices with LSPosed framework installed

## Options

| Option | What it does | Default |
| --- | --- | --- |
| Wi-Fi override mode: Off | No Wi-Fi MAC is overridden | |
| Wi-Fi override mode: Global override | One Standby MAC is applied to every Wi-Fi network | an upgrade with a saved global MAC and no rules starts here |
| Wi-Fi override mode: Per-SSID override | A MAC is applied only when the network being connected matches an enabled rule (SSID to MAC, add/edit/delete in the app, each with its own switch) | on for new installs |
| Override hotspot MAC | Uses the separate Hotspot MAC for the soft AP, never the Wi-Fi MAC. Independent of the Wi-Fi mode | off |
| Force enable MAC randomization | Forces the resource booleans below | on |

The status card (System MAC, Active MAC) is always shown. "Apply MAC Address" (Global) and "Apply to current network" (Per-SSID) re-apply immediately; rules themselves are saved as you edit them and take effect on the next connection.

Defaults in short: the hotspot MAC is never touched, and no Wi-Fi MAC is overridden unless an SSID rule matches. Other networks keep Android's normal MAC. The app refuses a Hotspot MAC equal to a Wi-Fi MAC (a duplicate stops the hotspot from starting).

SSID matching strips the surrounding quotes and is exact and case-sensitive. Hidden/empty SSIDs never match. Non-UTF-8 SSIDs are reported by Android as hex and will not match a text rule. Rules are read on every call, so no reboot is needed after changing them.

## Implementation

On modern Android, [the Wi-Fi subsystem](https://android.googlesource.com/platform/packages/modules/Wifi/+/refs/heads/main/service/java/com/android/server/wifi/WifiNative.java) is capable of randomizing device MAC address per network or per connection. This module hooks the following system server methods,

- `WifiNative.setStaMacAddress()`: replaces the client MAC (per rule or global)
- `WifiNative.setApMacAddress()`: replaces the hotspot MAC only when the hotspot override is on
- `ClientModeImpl.configureRandomizedMacAddress()` and `setCurrentMacToFactoryMac()`: only record the target SSID for the duration of the call, because `setStaMacAddress()` does not know it (checked against AOSP android15-release: both call it synchronously on the same thread). If the framework skips the call because its MAC already equals the current one, the module sets the rule MAC itself.

"Apply" acts on the Wi-Fi interface only and re-sets the MAC if the currently connected SSID has a matching rule. It runs from a broadcast thread rather than the Wi-Fi handler thread and disconnects Wi-Fi briefly.

Every hook is installed independently and only logs on failure. `logcat -s MACEditor` (or the LSPosed log) shows "hook fired" once per hook as proof it is actually reached.

For better compatibility, the module can also be used to force enable [MAC randomization](https://source.android.com/docs/core/connect/wifi-mac-randomization) by specifying the following resource booleans,
- `config_wifi_connected_mac_randomization_supported`: support for standard Wi-Fi
- `config_wifi_p2p_mac_randomization_supported`: support for Wi-Fi Direct or P2P
- `config_wifi_ap_mac_randomization_supported`: support for mobile hotspot

This is useful on devices where the hardware and chipset drivers do support MAC randomization, but the device vendor does not implement proper software support. This can also happen on some alternative Android builds where MAC randomization is not explicitly enabled. 

## Testing

The hooks run inside `system_server` and cannot be exercised in CI. CI only runs the JVM unit tests (MAC validation, SSID rule matching) and builds the release APK. Manual test plan (tested target: Android 15, LSPosed 2.2.0):

1. Add a rule `MySSID` -> `02:11:22:33:44:55`, enable the master switch, reconnect to `MySSID`: the Wi-Fi MAC is the rule MAC.
2. Connect to a different SSID: the MAC is Android's usual one (unchanged).
3. Disable the rule and reconnect: unchanged. Reconnect right after a previous connection to the same SSID to cover the "framework skipped" path.
4. Tap Apply while connected to `MySSID`: MAC re-applied; on another SSID: "No enabled rule matches".
5. Hotspot with "Override hotspot MAC" off: starts normally with Android's MAC.
6. Hotspot with it on and a distinct Hotspot MAC: starts and uses that MAC.

### Note for Qualcomm devices

Hardware support on certain chipsets can be checked by looking at `/vendor{/etc/wifi/kiwi_v2,firmware/wlan/qca_cld}/WCNSS_qcom_cfg.ini`. For legacy Qualcomm devices without MAC randomization support, consider editing `wlan_mac.bin` or `/sys/wifi/mac_addr` directly instead of using this module.

## Credits

- [David Berdik](https://f-droid.org/repo/com.berdik.macsposed_6_src.tar.gz) for the initial open-source system server hook implementation