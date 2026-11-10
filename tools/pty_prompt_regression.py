#!/usr/bin/env python3
"""Headless regression test for the PTY job-control deadlock.

The interactive terminal once froze on the first Enter: the traced shell was left
in a background process group, so its first read() was SIGTTIN-suspended and no
output ever appeared after "SUPERVISOR: Redirected execve ...". This script drives
`probe_ptrace` through a real PTY, feeds a command once the prompt appears, and
asserts both the prompt and the command output come back over master_fd.

Prerequisites (host):
  * a statically linked or host build of probe_ptrace.c
  * a BusyBox binary named libbusybox.so in the native lib directory
  * an extracted rootfs

Example:
  gcc -O0 -g -o /tmp/probe_ptrace app/src/main/cpp/probe_ptrace.c
  mkdir -p /tmp/native && cp app/src/main/jniLibs/x86_64/libbusybox.so /tmp/native/
  mkdir -p /tmp/rootfs && tar -xzf app/src/main/assets/rootfs/alpine-minirootfs-x86_64.tar.gz -C /tmp/rootfs
  VPPORTABLE_ROOTFS=/tmp/rootfs VPPORTABLE_NATIVE=/tmp/native \
      tools/pty_prompt_regression.py /tmp/probe_ptrace

Exit code 0 = prompt and command output captured, 2 = frozen after execve.
"""
import errno
import os
import pty
import select
import signal
import sys
import time

MARKER = "VPPORTABLE_PROMPT_OK_9f3c"
SUP = sys.argv[1] if len(sys.argv) > 1 else "/tmp/probe_ptrace"
TIMEOUT = float(sys.argv[2]) if len(sys.argv) > 2 else 6.0

ROOTFS = os.environ.get("VPPORTABLE_ROOTFS", "/tmp/rootfs")
NATIVE = os.environ.get("VPPORTABLE_NATIVE", "/tmp/jnilibs")


def run():
    pid, master = pty.fork()
    if pid == 0:
        os.chdir(ROOTFS)
        env = dict(
            os.environ,
            TERM="xterm-256color",
            HOME="/root",
            PATH="/bin:/sbin:/usr/bin:/usr/sbin:/system/bin",
            PROOT_NO_SECCOMP="1",
            PS1="vportable:\\w # ",
        )
        os.execve(SUP, [SUP, ROOTFS, NATIVE, "/bin/sh", "-i"], env)

    os.set_blocking(master, False)
    buf = bytearray()
    deadline = time.time() + TIMEOUT
    sent = False
    saw_prompt = False
    saw_marker = False
    while time.time() < deadline:
        r, _, _ = select.select([master], [], [], 0.2)
        if master in r:
            try:
                chunk = os.read(master, 4096)
            except OSError as e:
                if e.errno == errno.EIO:
                    break
                if e.errno == errno.EAGAIN:
                    continue
                raise
            if not chunk:
                break
            buf += chunk
            if b"# " in chunk or b"vportable" in chunk:
                saw_prompt = True
                if not sent:
                    time.sleep(0.3)
                    os.write(master, ("echo " + MARKER + "\n").encode())
                    sent = True
                    deadline = time.time() + 3.0
            if MARKER.encode() in chunk:
                saw_marker = True
        try:
            w, _ = os.waitpid(pid, os.WNOHANG)
            if w == pid:
                break
        except ChildProcessError:
            break

    try:
        os.kill(pid, signal.SIGKILL)
    except ProcessLookupError:
        pass

    print(f"supervisor   : {SUP}")
    print(f"bytes        : {len(buf)}")
    print(f"prompt seen  : {saw_prompt}")
    print(f"marker echoed: {saw_marker}")
    print(f"tail         : {bytes(buf[-260:])!r}")
    if saw_prompt and saw_marker:
        print("RESULT: PASS")
        return 0
    print("RESULT: FAIL (frozen after execve)")
    return 2


if __name__ == "__main__":
    sys.exit(run())
