# Virtual Flasher ("V-Flash Engine"): Technical Specification

## 1. Overview & Architectural Goals
The **V-Flash Engine** is VPortable's non-destructive rootfs management and add-on package installation subsystem. In traditional Android environments, flashing add-ons (such as MicroG or OpenGApps) requires custom recovery environments (TWRP, ClockworkMod) operating with root privileges to mount `/system` as read-write, execute recovery shell scripts, and write directly to physical block devices.

In VPortable (`com.m5dev.vportable`), the guest environment runs entirely in user-space without kernel root privileges or raw block storage access. The V-Flash Engine simulates a recovery environment by operating directly on file trees within isolated app storage (`/data/data/com.m5dev.vportable/rootfs`).

### Key Design Requirements:
1. **Clean Rootfs Bootstrapping:** Decompress and initialize a lightweight Android 9 (Pie) base image from a compressed archive (`.7z` / `.tar.xz`).
2. **Virtual Recovery Simulation:** Parse zip packages (MicroG / OpenGApps), analyze `updater-script` / installation manifests, and merge system files directly into the rootfs.
3. **Non-Destructive Delta Merging:** Support atomic package updates, overlays, and add-on removal without corrupting the core system image.
4. **Permissions & Ownership Sanitization:** Explicitly apply POSIX permissions, UID/GID owner mappings (`root`, `system`), and SELinux xattr stubs without requiring kernel `chown` or `chmod` permissions.

---

## 2. Base Rootfs Extraction & Unpack Logic

VPortable inspects and draws inspiration from Twoyi's `RomManager.java` implementation (`com.hzy.libp7zip.P7ZipApi` and `SevenZFile`), optimizing archive extraction for performance, atomic safety, and Bionic symlink preservation.

### 2.1 Storage Layout
All rootfs files are isolated inside host application storage:
```
/data/data/com.m5dev.vportable/
├── files/
│   └── rootfs_archive.7z             # Staged compressed archive
└── rootfs/                            # Target extracted rootfs root
    ├── system/                        # Android System Partition
    │   ├── app/
    │   ├── priv-app/                  # Privileged System Apps
    │   ├── etc/
    │   │   └── permissions/           # System Permission XML Whitelists
    │   └── framework/
    ├── data/                          # Android Data Partition
    ├── dev/                           # Virtualized Device Nodes (Directory-backed)
    ├── proc/                          # Procfs Mountpoint
    └── sys/                           # Sysfs Mountpoint
```

### 2.2 Extraction Pipeline
```mermaid
flowchart TD
    A[Start V-Flash Extraction] --> B{Check Rootfs Target}
    B -- Target Exists --> C[Validate Manifest integrity `rom.ini`]
    B -- Clean Setup --> D[Allocate Stage Dir `/rootfs_tmp`]
    D --> E[Execute 7z Native Extraction via `P7ZipApi.executeCommand`]
    E --> F[Iterate Extracted Nodes & Restore POSIX Symlinks]
    F --> G[Enforce Executable Bit `chmod 0755` on `/system/bin/*`]
    G --> H[Atomic Rename `/rootfs_tmp` -> `/rootfs`]
    H --> I[Write Build Properties `rom.ini`]
    I --> J[Extraction Complete]
```

### 2.3 Symlink & Special File Preservation
Standard Java `ZipInputStream` and basic unarchivers drop POSIX symbolic links and file modes. The V-Flash engine utilizes native 7z extraction (`lib7z` native library) paired with a post-processing pass:
- **Symlink Restoration:** Symlink entry metadata recorded in `.7z` headers are reconstructed using native POSIX `symlinkat(target, dirfd, linkpath)` syscalls.
- **File Permissions:** Executables under `/system/bin`, `/system/xbin`, and shared libraries under `/system/lib64` receive POSIX mode `0755` (`rwxr-xr-x`) or `0644` (`rw-r--r--`).

---

## 3. Flashable Package (ZIP) Processing Engine

V-Flash processes add-on packages such as **MicroG GmsCore** and **OpenGApps** without executing arbitrary recovery shell binaries, preventing incompatible recovery scripts from failing in user-space.

### 3.1 OpenGApps & MicroG Package Analysis
1. **OpenGApps Structures:**
   - Contains architecture-specific tarball payloads (`app-priv.tar.xz`, `framework.tar.xz`, `etc.tar.xz`).
   - Contains installer script `bk-install.sh` and permission definitions `privapp-permissions-*.xml`.
2. **MicroG Package Structures:**
   - Package structure includes `GmsCore.apk`, `ServicesFrameworkProxy.apk`, `FakeStore.apk`, and system permission XMLs.

### 3.2 Automated Package Parser & Injector Algorithm
The V-Flash engine bypasses Edify/Installer scripts (`META-INF/com/google/android/update-binary`) and directly performs a **Manifest-Driven Installation**:

```
Package ZIP
  ├── Core APKs/
  │     ├── GmsCore.apk           --> /system/priv-app/GmsCore/GmsCore.apk
  │     ├── GsfProxy.apk          --> /system/priv-app/GsfProxy/GsfProxy.apk
  │     └── FakeStore.apk         --> /system/priv-app/FakeStore/FakeStore.apk
  ├── System Permissions/
  │     └── privapp-permissions-google.xml --> /system/etc/permissions/privapp-permissions-google.xml
  └── Native Libraries/
        └── lib/arm64-v8a/*.so    --> /system/lib64/
```

---

## 4. UID / GID Ownership & Permission Mapping Table

In a standard Android system, file ownership determines security boundaries. Under PRoot, host execution runs entirely under a single Android app UID (e.g., `u0_a142` = UID `10142`). V-Flash writes files with host UID/GID but records virtual metadata in a PRoot translation database or metadata file.

| Target Path | Target Owner (Virtual UID/GID) | Target Mode | Description |
| :--- | :--- | :--- | :--- |
| `/system/bin/*` | `root:shell` (0:2000) | `0755` | System binaries and executables |
| `/system/priv-app/*` | `root:root` (0:0) | `0755` (dir) / `0644` (file) | Privileged system applications |
| `/system/etc/permissions/*` | `root:root` (0:0) | `0644` | Framework permission grants |
| `/data/system/*` | `system:system` (1000:1000) | `0771` | System configuration data |
| `/data/app/*` | `system:system` (1000:1000) | `0771` | Installed user applications |

### Permission Injection Snippet (MicroG Signature Spoofing Grant)
To enable signature spoofing and Google service emulation for MicroG, V-Flash injects a custom permission XML file directly into `/system/etc/permissions/privapp-permissions-microg.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<permissions>
    <privapp-permissions package="com.google.android.gms">
        <permission name="android.permission.FAKE_PACKAGE_SIGNATURE"/>
        <permission name="android.permission.INSTALL_LOCATION_PROVIDER"/>
        <permission name="android.permission.CHANGE_DEVICE_IDLE_TEMP_WHITELIST"/>
        <permission name="android.permission.UPDATE_DEVICE_STATS"/>
    </privapp-permissions>
</permissions>
```

---

## 5. Non-Destructive Delta Management & Overlays

To allow users to modify, reset, or upgrade add-ons without destroying their rootfs, V-Flash implements a lightweight overlay catalog system:

1. **Manifest Tracker (`/data/data/com.m5dev.vportable/vflash_manifest.json`):** Tracks every file injected by a specific ZIP package hash.
2. **Atomic Uninstall:** To remove an add-on, V-Flash reads the manifest, removes injected binaries and permission files, and triggers a PRoot cache flush.
3. **Safety Backup State:** Before applying an add-on, V-Flash creates a lightweight differential snapshot of affected directories (`/system/priv-app`, `/system/etc`).
