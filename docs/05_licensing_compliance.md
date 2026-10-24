# Licensing & Open-Source Compliance Specification

## 1. Executive Summary & License Selection
**VPortable** (`com.m5dev.vportable`) is licensed under the **GNU General Public License v3.0 (GPLv3)**.

The selection of GPLv3 is a deliberate strategic decision by the `M5Devs` organization to safeguard the project against proprietary commercial monetization, closed-source forks, and anti-competitive lock-in. By adopting GPLv3, any third-party distributor or vendor that modifies or embeds VPortable must release their complete source code under the same GPLv3 terms.

---

## 2. Rationale for GNU General Public License v3 (GPLv3)

1. **Anti-Monopoly & Anti-Closed Fork Protections:**
   Prevents commercial entities from taking VPortable's containerization engine, enhancing it with proprietary modifications, and distributing it as a closed-source paid product without contributing back to the open-source ecosystem.
2. **Protection Against Patent Ambush (Section 11):**
   GPLv3 provides explicit patent licenses from contributors to users, ensuring that upstream patent holders cannot assert patent infringement against users of VPortable.
3. **Anti-Tivoization Clause (Section 6):**
   Requires distributors who install GPLv3 software on hardware devices to provide users with the complete Installation Information required to modify and run updated versions of the software on that hardware.

---

## 3. Upstream Component & License Matrix

VPortable integrates, links, and adapts components from several open-source projects. The matrix below outlines license compatibility and isolation boundaries:

| Subsystem / Component | Primary Source Repository | License | Integration Method | License Compatibility & Isolation Boundary |
| :--- | :--- | :--- | :--- | :--- |
| **PRoot Engine Fork** | [Termux PRoot](https://github.com/termux/proot) / [Mainline PRoot](https://github.com/proot-me/proot) | **GPLv2.0 or later** | Dynamic process boundary (`execve` process invocation) | GPLv2+ is upward-compatible with GPLv3. Process isolation ensures clear architectural separation. |
| **Guest Android Rootfs** | [Android Open Source Project (AOSP)](https://android.googlesource.com/) | **Apache 2.0** / BSD | Guest System Binaries executed inside isolated rootfs | Permissive Apache 2.0 code in guest user-space remains untouched under standard AOSP license terms. |
| **Gfxstream / Goldfish** | [AOSP Gfxstream](https://android.googlesource.com/platform/hardware/google/gfxstream/) | **Apache 2.0** / MIT | Native shared library compilation (`libgfxstream_host.so`) | Apache 2.0 code linked into a GPLv3 binary is permitted under GPLv3 (GPLv3 Section 7 compatibility). |
| **MicroG GmsCore** | [MicroG Project](https://github.com/microg/GmsCore) | **Apache 2.0** | Add-on package processed by V-Flash engine | Distributed as separate flashable add-on package; does not affect host license. |
| **OpenGApps Scripts** | [OpenGApps Project](https://github.com/opengapps/opengapps) | **GPLv3** | Reference for installer logic in V-Flash | Compatible directly with VPortable's GPLv3 license. |
| **7-Zip Library** | `libp7zip` / Native C++ | **LGPLv2.1+** with unRAR restriction | Linked native library (`libp7zip.so`) | LGPL allows dynamic linking into VPortable core binaries. |

---

## 4. Compliance & Attribution Requirements

To remain in strict compliance with all upstream licenses, VPortable adheres to the following rules:

### 4.1 Notice & Attribution Display
The VPortable Application includes an **Open Source Licenses** screen inside the app settings (`Settings -> About -> Open Source Licenses`) displaying:
- Complete text of the GPLv3 license (`LICENSE`).
- Full copyright notices and original license texts for PRoot, AOSP, MicroG, and libp7zip.

### 4.2 Source Code Distribution
In accordance with GPLv3 Section 6:
- The complete corresponding source code for VPortable—including native Rust bridge scripts, C/C++ PRoot modifications, and host Android application code—is made available in this repository.
- Build scripts and instructions required to recompile binaries (`gradlew`, CMake, and Cargo configs) are strictly maintained in working condition within the repository.
