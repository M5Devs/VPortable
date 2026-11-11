#!/usr/bin/env python3
"""Fail the build if a shipped native helper has the wrong CPU architecture.

`libbusybox.so` and `libproot.so` are prebuilt binaries committed under
`app/src/main/jniLibs/<abi>/`. Android packages them verbatim (native libraries
are stored uncompressed and made executable), so nothing in the build toolchain
checks that they are really built for the ABI of the directory they sit in. A
wrong-ABI binary only fails at runtime, as `execv ...: Exec format error`, which
the terminal surfaces as "Terminal Offline" the instant the shell is launched.

That happened: `jniLibs/arm64-v8a/libbusybox.so` was an ELF32 ARM (armv7)
executable, so the shell died immediately on every arm64 device. This script is
the guard.

Usage:
    tools/check_native_abi.py [jniLibs_dir]

Exit code 0 = every binary matches its directory, 1 = at least one mismatch.
"""
import os
import struct
import sys

# (ELF class, e_machine) that each Android ABI directory must contain.
ABI_EXPECTED = {
    "arm64-v8a": (2, 183),   # ELF64, EM_AARCH64
    "armeabi-v7a": (1, 40),  # ELF32, EM_ARM
    "x86_64": (2, 62),       # ELF64, EM_X86_64
    "x86": (1, 3),           # ELF32, EM_386
}

CLASS_NAME = {1: "ELF32", 2: "ELF64"}
MACHINE_NAME = {
    3: "Intel 80386",
    40: "ARM",
    62: "x86-64",
    183: "AArch64",
}


def describe(cls, machine):
    return f"{CLASS_NAME.get(cls, cls)} {MACHINE_NAME.get(machine, machine)}"


def elf_identity(path):
    """Return (ei_class, e_machine) for an ELF file, or None if it is not ELF."""
    with open(path, "rb") as fh:
        header = fh.read(20)
    if len(header) < 20 or header[:4] != b"\x7fELF":
        return None
    ei_class = header[4]
    ei_data = header[5]
    endian = "<" if ei_data == 1 else ">"
    (e_machine,) = struct.unpack(endian + "H", header[18:20])
    return ei_class, e_machine


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/jniLibs"
    if not os.path.isdir(root):
        print(f"check_native_abi: {root} does not exist", file=sys.stderr)
        return 1

    failures = []
    checked = 0

    for abi in sorted(os.listdir(root)):
        abi_dir = os.path.join(root, abi)
        if not os.path.isdir(abi_dir):
            continue
        expected = ABI_EXPECTED.get(abi)
        if expected is None:
            print(f"check_native_abi: unknown ABI directory '{abi}', skipping")
            continue

        for name in sorted(os.listdir(abi_dir)):
            path = os.path.join(abi_dir, name)
            if not os.path.isfile(path):
                continue
            identity = elf_identity(path)
            if identity is None:
                continue  # not an ELF; leave it alone
            checked += 1
            if identity != expected:
                failures.append(
                    f"{path}: is {describe(*identity)} but '{abi}' requires "
                    f"{describe(*expected)}"
                )
            else:
                print(f"ok   {path}: {describe(*identity)}")

    print()
    if failures:
        print("check_native_abi: FAILED", file=sys.stderr)
        for line in failures:
            print("  " + line, file=sys.stderr)
        print(
            "\nA binary in the wrong ABI directory cannot be exec'd on the target "
            "device (ENOEXEC), which kills the terminal shell at startup.",
            file=sys.stderr,
        )
        return 1

    print(f"check_native_abi: OK ({checked} binaries checked)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
