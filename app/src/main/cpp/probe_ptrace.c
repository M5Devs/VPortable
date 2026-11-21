#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <fcntl.h>
#include <dlfcn.h>
#include <sys/ptrace.h>
#include <sys/wait.h>
#include <sys/uio.h>
#include <sys/user.h>
#include <sys/syscall.h>
#include <sys/stat.h>
#include <elf.h>
#include <signal.h>
#include <termios.h>
#include <dirent.h>
#include <limits.h>

#if defined(__ANDROID__)
#include <android/log.h>
#endif

#ifndef NT_PRSTATUS
#define NT_PRSTATUS 1
#endif
#ifndef PTRACE_GETREGSET
#define PTRACE_GETREGSET 0x4204
#endif
#ifndef PTRACE_SETREGSET
#define PTRACE_SETREGSET 0x4205
#endif
#ifndef PTRACE_SETOPTIONS
#define PTRACE_SETOPTIONS 0x4200
#endif
#ifndef PTRACE_O_TRACESYSGOOD
#define PTRACE_O_TRACESYSGOOD 0x00000001
#endif
#ifndef PTRACE_O_TRACEFORK
#define PTRACE_O_TRACEFORK 0x00000002
#endif
#ifndef PTRACE_O_TRACEVFORK
#define PTRACE_O_TRACEVFORK 0x00000004
#endif
#ifndef PTRACE_O_TRACECLONE
#define PTRACE_O_TRACECLONE 0x00000008
#endif
#ifndef PTRACE_O_TRACEEXEC
#define PTRACE_O_TRACEEXEC 0x00000010
#endif
#ifndef PTRACE_GETEVENTMSG
#define PTRACE_GETEVENTMSG 0x4201
#endif
#ifndef PTRACE_EVENT_FORK
#define PTRACE_EVENT_FORK 1
#endif
#ifndef PTRACE_EVENT_VFORK
#define PTRACE_EVENT_VFORK 2
#endif
#ifndef PTRACE_EVENT_CLONE
#define PTRACE_EVENT_CLONE 3
#endif
#ifndef PTRACE_EVENT_EXEC
#define PTRACE_EVENT_EXEC 4
#endif
#ifndef AT_FDCWD
#define AT_FDCWD -100
#endif

#ifndef __NR_openat
#if defined(__x86_64__)
#define __NR_openat 257
#elif defined(__aarch64__)
#define __NR_openat 56
#elif defined(__i386__)
#define __NR_openat 295
#elif defined(__arm__)
#define __NR_openat 322
#endif
#endif

#ifndef __NR_newfstatat
#if defined(__x86_64__)
#define __NR_newfstatat 262
#elif defined(__aarch64__)
#define __NR_newfstatat 79
#elif defined(__i386__)
#define __NR_newfstatat 327
#elif defined(__arm__)
#define __NR_newfstatat 327
#endif
#endif

// aarch64 and arm have no legacy open/stat/lstat (only the *at variants). On
// those targets the values are set to an impossible syscall number so the
// comparisons below still compile and are permanently false, rather than
// needing preprocessor guards inside the dispatch chain.
#ifndef __NR_open
#if defined(__x86_64__)
#define __NR_open 2
#elif defined(__i386__)
#define __NR_open 5
#else
#define __NR_open (-1)
#endif
#endif

#ifndef __NR_stat
#if defined(__x86_64__)
#define __NR_stat 4
#elif defined(__i386__)
#define __NR_stat 106
#else
#define __NR_stat (-1)
#endif
#endif

#ifndef __NR_lstat
#if defined(__x86_64__)
#define __NR_lstat 6
#elif defined(__i386__)
#define __NR_lstat 107
#else
#define __NR_lstat (-1)
#endif
#endif

#ifndef __NR_execve
#if defined(__x86_64__)
#define __NR_execve 59
#elif defined(__aarch64__)
#define __NR_execve 221
#elif defined(__i386__)
#define __NR_execve 11
#elif defined(__arm__)
#define __NR_execve 11
#endif
#endif

#ifndef __NR_chdir
#if defined(__x86_64__)
#define __NR_chdir 80
#elif defined(__aarch64__)
#define __NR_chdir 49
#elif defined(__i386__) || defined(__arm__)
#define __NR_chdir 12
#endif
#endif

#ifndef __NR_getcwd
#if defined(__x86_64__)
#define __NR_getcwd 79
#elif defined(__aarch64__)
#define __NR_getcwd 17
#elif defined(__i386__) || defined(__arm__)
#define __NR_getcwd 183
#endif
#endif

// UID/GID syscalls. These are already defined by <sys/syscall.h> for every
// supported target, so the numbers below are only a fallback. Note they differ
// per architecture: 174-177 is the *aarch64* numbering and collides with
// create_module/init_module/delete_module/get_kernel_syms on x86_64, where the
// real values are 102/107/104/108.
#ifndef __NR_getuid
#if defined(__aarch64__)
#define __NR_getuid 174
#define __NR_geteuid 175
#define __NR_getgid 176
#define __NR_getegid 177
#elif defined(__x86_64__)
#define __NR_getuid 102
#define __NR_geteuid 107
#define __NR_getgid 104
#define __NR_getegid 108
#elif defined(__i386__)
#define __NR_getuid 24
#define __NR_geteuid 49
#define __NR_getgid 47
#define __NR_getegid 50
#elif defined(__arm__)
#define __NR_getuid 24
#define __NR_geteuid 49
#define __NR_getgid 47
#define __NR_getegid 50
#endif
#endif

extern char **environ;

#if defined(__x86_64__)
typedef struct user_regs_struct arch_regs_t;
#define ARCH_SP(regs) ((uintptr_t)(regs).rsp)
#define ARCH_SYSCALL_NR(regs) ((long)(regs).orig_rax)
#define ARCH_RET(regs) ((long)(regs).rax)
#define ARCH_ARG1(regs) ((uintptr_t)(regs).rdi)
#define ARCH_ARG2(regs) ((uintptr_t)(regs).rsi)
#define SET_ARCH_ARG1(regs, val) ((regs).rdi = (unsigned long long)(val))
#define SET_ARCH_ARG2(regs, val) ((regs).rsi = (unsigned long long)(val))
// The syscall return value lives in rax, NOT in the ARG1 register (rdi).
#define SET_ARCH_RET(regs, val) ((regs).rax = (unsigned long long)(val))
#elif defined(__aarch64__)
typedef struct user_pt_regs arch_regs_t;
#define ARCH_SP(regs) ((uintptr_t)(regs).sp)
#define ARCH_SYSCALL_NR(regs) ((long)(regs).regs[8])
#define ARCH_RET(regs) ((long)(regs).regs[0])
#define ARCH_ARG1(regs) ((uintptr_t)(regs).regs[0])
#define ARCH_ARG2(regs) ((uintptr_t)(regs).regs[1])
#define SET_ARCH_ARG1(regs, val) ((regs).regs[0] = (__u64)(val))
#define SET_ARCH_ARG2(regs, val) ((regs).regs[1] = (__u64)(val))
// On aarch64 the return value shares regs[0] with ARG1.
#define SET_ARCH_RET(regs, val) ((regs).regs[0] = (__u64)(val))
#elif defined(__arm__)
typedef struct user_regs arch_regs_t;
#define ARCH_SP(regs) ((uintptr_t)(regs).uregs[13])
#define ARCH_SYSCALL_NR(regs) ((long)(regs).uregs[7])
#define ARCH_RET(regs) ((long)(regs).uregs[0])
#define ARCH_ARG1(regs) ((uintptr_t)(regs).uregs[0])
#define ARCH_ARG2(regs) ((uintptr_t)(regs).uregs[1])
#define SET_ARCH_ARG1(regs, val) ((regs).uregs[0] = (unsigned long)(val))
#define SET_ARCH_ARG2(regs, val) ((regs).uregs[1] = (unsigned long)(val))
// On arm the return value shares uregs[0] with ARG1.
#define SET_ARCH_RET(regs, val) ((regs).uregs[0] = (unsigned long)(val))
#else
typedef struct user_regs_struct arch_regs_t;
#define ARCH_SP(regs) ((uintptr_t)(regs).esp)
#define ARCH_SYSCALL_NR(regs) ((long)(regs).orig_eax)
#define ARCH_RET(regs) ((long)(regs).eax)
#define ARCH_ARG1(regs) ((uintptr_t)(regs).ebx)
#define ARCH_ARG2(regs) ((uintptr_t)(regs).ecx)
#define SET_ARCH_ARG1(regs, val) ((regs).ebx = (unsigned long)(val))
#define SET_ARCH_ARG2(regs, val) ((regs).ecx = (unsigned long)(val))
// On i386 the return value is eax; orig_eax holds the syscall number.
#define SET_ARCH_RET(regs, val) ((regs).eax = (unsigned long)(val))
#endif

// ---------------- Diagnostics ----------------
//
// The supervisor shares the PTY with the guest shell, so anything it prints to
// stdout/stderr lands in the middle of the shell's output stream and corrupts
// the terminal. Diagnostics go to Android logcat instead:
//     adb logcat -s VPortableSupervisor
// On the host build (used by the headless regression test) they go to stderr,
// which is the terminal the test drives.
#define SUP_TAG "VPortableSupervisor"

#if defined(__ANDROID__)
#define sup_log(level, ...) \
    __android_log_print(level, SUP_TAG, __VA_ARGS__)
#else
#define sup_log(level, ...) \
    do { fprintf(stderr, "[%s] ", SUP_TAG); fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n"); } while (0)
#endif

#define SUP_INFO(...)  sup_log(ANDROID_LOG_INFO, __VA_ARGS__)
#define SUP_WARN(...)  sup_log(ANDROID_LOG_WARN, __VA_ARGS__)
#define SUP_ERROR(...) sup_log(ANDROID_LOG_ERROR, __VA_ARGS__)

// ---------------- Process Tracking & Process Group Cleanup ----------------
#define MAX_TRACKED_PIDS 64
typedef struct {
    pid_t pid;
    int in_syscall;
    long last_syscall;
    int is_active;
    char cwd[1024];
    char pending_chdir[1024];
} pid_state_t;

static pid_state_t pid_table[MAX_TRACKED_PIDS];
static pid_t g_child_pgid = 0;

static pid_state_t* get_or_create_pid(pid_t pid) {
    int free_idx = -1;
    for (int i = 0; i < MAX_TRACKED_PIDS; i++) {
        if (pid_table[i].is_active && pid_table[i].pid == pid) {
            return &pid_table[i];
        }
        if (!pid_table[i].is_active && free_idx == -1) {
            free_idx = i;
        }
    }
    if (free_idx != -1) {
        pid_table[free_idx].pid = pid;
        pid_table[free_idx].in_syscall = 0;
        pid_table[free_idx].last_syscall = -1;
        pid_table[free_idx].is_active = 1;
        strcpy(pid_table[free_idx].cwd, "/");
        pid_table[free_idx].pending_chdir[0] = '\0';
        return &pid_table[free_idx];
    }
    return NULL;
}

static void remove_pid(pid_t pid) {
    for (int i = 0; i < MAX_TRACKED_PIDS; i++) {
        if (pid_table[i].is_active && pid_table[i].pid == pid) {
            pid_table[i].is_active = 0;
            break;
        }
    }
}

static int count_active_pids() {
    int count = 0;
    for (int i = 0; i < MAX_TRACKED_PIDS; i++) {
        if (pid_table[i].is_active) count++;
    }
    return count;
}

static void cleanup_process_group(void) {
    if (g_child_pgid > 0) {
        killpg(g_child_pgid, SIGKILL);
        int status;
        while (waitpid(-g_child_pgid, &status, __WALL | WNOHANG) > 0);
    }
}

// Hand the controlling terminal back to the supervisor's process group. Must be
// called with SIGTTOU ignored, otherwise the call itself suspends us.
static void restore_terminal_foreground(void) {
    if (isatty(STDIN_FILENO)) {
        tcsetpgrp(STDIN_FILENO, getpgrp());
    }
}

// Move the controlling terminal to `pgid` and confirm the kernel accepted it.
// SIGTTOU must already be ignored, otherwise this call suspends us when we are
// not the current foreground group. Returns 1 on success.
static int acquire_terminal_foreground(pid_t pgid) {
    if (tcsetpgrp(STDIN_FILENO, pgid) != 0) {
        return 0;
    }
    return tcgetpgrp(STDIN_FILENO) == pgid;
}

// Guarantee the traced shell can read the terminal.
//
// The child joins its own process group so the supervisor can keep job control
// isolated, but a shell left in a background group has its first read()
// SIGTTIN-suspended and the whole session appears frozen. If the terminal
// handover fails for any reason, put the child back into the supervisor's
// (foreground) group instead of silently freezing.
static void establish_terminal_foreground(pid_t child) {
    if (!isatty(STDIN_FILENO)) {
        return;
    }
    signal(SIGTTOU, SIG_IGN);
    if (acquire_terminal_foreground(child)) {
        return;
    }
    if (setpgid(child, getpgrp()) == 0) {
        SUP_WARN("could not give the terminal to pgid %d (%s); sharing the "
                 "supervisor's process group so input still reaches the shell",
                 child, strerror(errno));
    } else {
        SUP_ERROR("terminal handover to pgid %d failed (%s) and the fallback also "
                  "failed (%s); interactive input may not work",
                  child, strerror(errno), strerror(errno));
    }
}

static void signal_handler(int sig) {
    cleanup_process_group();
    restore_terminal_foreground();
    _exit(128 + sig);
}

// ---------------- Generic Mount Table (Expanded for Alpine Userland) ----------------
#define MOUNT_TABLE_SIZE 14
typedef struct {
    const char *guest_prefix;
    char host_target[1024];
} mount_entry_t;

static mount_entry_t mount_table[MOUNT_TABLE_SIZE];
static char g_rootfs_dir[1024];

// Alpine's minirootfs applets are absolute symlinks into /bin/busybox
// ("ls -> /bin/busybox"). The kernel resolves a symlink target against the real
// filesystem, not our mount table, so every such applet pointed at the device's
// /bin and the shell reported "ls: not found" once path rewriting started
// working. Rewrite each absolute link to a relative one so it resolves inside
// the rootfs. Idempotent: relative links are left alone, so repeated sessions
// are cheap.
static void relativize_symlinks(const char *dir_path) {
    DIR *dir = opendir(dir_path);
    if (!dir) return;

    // Depth of dir_path below the rootfs, e.g. "<rootfs>/usr/lib" -> 2. Counted
    // from the rootfs prefix, not from the absolute path, or the resulting
    // "../.." chain would climb past the container root onto the host.
    size_t root_len = strlen(g_rootfs_dir);
    int depth = 0;
    if (strncmp(dir_path, g_rootfs_dir, root_len) == 0) {
        for (const char *p = dir_path + root_len; *p; p++) {
            if (*p == '/') depth++;
        }
    }

    struct dirent *ent;
    while ((ent = readdir(dir)) != NULL) {
        if (strcmp(ent->d_name, ".") == 0 || strcmp(ent->d_name, "..") == 0) continue;

        char full[PATH_MAX];
        if (snprintf(full, sizeof(full), "%s/%s", dir_path, ent->d_name) >= (int) sizeof(full)) continue;

        struct stat lst;
        if (lstat(full, &lst) != 0) continue;

        if (S_ISDIR(lst.st_mode)) {
            relativize_symlinks(full);
            continue;
        }
        if (!S_ISLNK(lst.st_mode)) continue;

        char target[PATH_MAX];
        ssize_t len = readlink(full, target, sizeof(target) - 1);
        if (len <= 0) continue;
        target[len] = '\0';

        // Already relative: resolves inside the rootfs on its own.
        if (target[0] != '/') continue;

        char relative[PATH_MAX];
        size_t off = 0;
        relative[0] = '\0';
        for (int i = 0; i < depth && off + 3 < sizeof(relative); i++) {
            relative[off++] = '.';
            relative[off++] = '.';
            relative[off++] = '/';
        }
        relative[off] = '\0';

        // "/bin/busybox" -> "bin/busybox" so the "../" prefix is not doubled.
        const char *stripped = target;
        while (*stripped == '/') stripped++;

        char new_target[PATH_MAX];
        if (snprintf(new_target, sizeof(new_target), "%s%s", relative, stripped) >= (int) sizeof(new_target)) continue;

        char tmp_link[PATH_MAX + 8];
        if (snprintf(tmp_link, sizeof(tmp_link), "%s.vpnew", full) >= (int) sizeof(tmp_link)) continue;

        unlink(tmp_link);
        if (symlink(new_target, tmp_link) == 0 && rename(tmp_link, full) != 0) {
            unlink(tmp_link);
        }
    }
    closedir(dir);
}

static void init_mount_table(const char *rootfs_dir) {
    snprintf(g_rootfs_dir, sizeof(g_rootfs_dir), "%s", rootfs_dir);

    mount_table[0].guest_prefix = "/bin";
    snprintf(mount_table[0].host_target, sizeof(mount_table[0].host_target), "%s/bin", rootfs_dir);

    mount_table[1].guest_prefix = "/sbin";
    snprintf(mount_table[1].host_target, sizeof(mount_table[1].host_target), "%s/sbin", rootfs_dir);

    mount_table[2].guest_prefix = "/usr";
    snprintf(mount_table[2].host_target, sizeof(mount_table[2].host_target), "%s/usr", rootfs_dir);

    mount_table[3].guest_prefix = "/etc";
    snprintf(mount_table[3].host_target, sizeof(mount_table[3].host_target), "%s/etc", rootfs_dir);

    mount_table[4].guest_prefix = "/lib64";
    snprintf(mount_table[4].host_target, sizeof(mount_table[4].host_target), "%s/lib64", rootfs_dir);

    mount_table[5].guest_prefix = "/lib";
    snprintf(mount_table[5].host_target, sizeof(mount_table[5].host_target), "%s/lib", rootfs_dir);

    mount_table[6].guest_prefix = "/var";
    snprintf(mount_table[6].host_target, sizeof(mount_table[6].host_target), "%s/var", rootfs_dir);

    mount_table[7].guest_prefix = "/tmp";
    snprintf(mount_table[7].host_target, sizeof(mount_table[7].host_target), "%s/tmp", rootfs_dir);

    mount_table[8].guest_prefix = "/home";
    snprintf(mount_table[8].host_target, sizeof(mount_table[8].host_target), "%s/home", rootfs_dir);

    mount_table[9].guest_prefix = "/root";
    snprintf(mount_table[9].host_target, sizeof(mount_table[9].host_target), "%s/root", rootfs_dir);

    mount_table[10].guest_prefix = "/opt";
    snprintf(mount_table[10].host_target, sizeof(mount_table[10].host_target), "%s/opt", rootfs_dir);

    mount_table[11].guest_prefix = "/srv";
    snprintf(mount_table[11].host_target, sizeof(mount_table[11].host_target), "%s/srv", rootfs_dir);

    mount_table[12].guest_prefix = "/mnt";
    snprintf(mount_table[12].host_target, sizeof(mount_table[12].host_target), "%s/mnt", rootfs_dir);

    mount_table[13].guest_prefix = "/run";
    snprintf(mount_table[13].host_target, sizeof(mount_table[13].host_target), "%s/run", rootfs_dir);
}

static int is_host_path(const char *in_path) {
    // Paths the Android runtime itself needs must keep resolving against the
    // real filesystem: the dynamic linker, /proc for introspection, the PTY
    // device nodes and the app's own data directory all live on the host.
    // /mnt is deliberately absent: it is a guest directory in the rootfs, and
    // mapping it to the host would expose the device's mount points.
    static const char *host_prefixes[] = {
        "/system", "/apex", "/vendor", "/product", "/proc", "/dev",
        "/sys", "/data", "/storage"
    };
    for (size_t i = 0; i < sizeof(host_prefixes) / sizeof(host_prefixes[0]); i++) {
        size_t plen = strlen(host_prefixes[i]);
        if (strncmp(in_path, host_prefixes[i], plen) == 0 &&
            (in_path[plen] == '/' || in_path[plen] == '\0')) {
            return 1;
        }
    }
    return 0;
}

static int resolve_vfs_path(const char *in_path, char *out_path, size_t out_size) {
    if (is_host_path(in_path)) {
        return 0;
    }

    // Bare "/" maps to the rootfs root so "ls /" lists the container instead of
    // hitting the Android root and failing with EACCES. Checked before the
    // mount-table loop because no prefix matches "/" itself.
    if (strcmp(in_path, "/") == 0) {
        snprintf(out_path, out_size, "%s", g_rootfs_dir);
        return 1;
    }

    for (int i = 0; i < MOUNT_TABLE_SIZE; i++) {
        size_t plen = strlen(mount_table[i].guest_prefix);
        if (strncmp(in_path, mount_table[i].guest_prefix, plen) == 0) {
            if (in_path[plen] == '/' || in_path[plen] == '\0') {
                snprintf(out_path, out_size, "%s%s", mount_table[i].host_target, in_path + plen);
                return 1;
            }
        }
    }

    // Anything else is a guest-absolute path: map it under the rootfs so that
    // "/" is the container root rather than the device root. Without this a
    // bare "ls /" or an unmapped "/etc/os-release" hit the host filesystem,
    // which is why the shell reported "Permission denied" on "/" and could not
    // find files that exist in the rootfs.
    if (in_path[0] == '/' && g_rootfs_dir[0] != '\0') {
        snprintf(out_path, out_size, "%s%s", g_rootfs_dir, in_path);
        return 1;
    }
    return 0;
}

static void resolve_relative_or_absolute(const char *in_path, const char *cwd, char *out_path, size_t out_size) {
    if (in_path[0] == '/') {
        strncpy(out_path, in_path, out_size - 1);
        out_path[out_size - 1] = '\0';
    } else {
        if (strcmp(cwd, "/") == 0) {
            snprintf(out_path, out_size, "/%s", in_path);
        } else {
            snprintf(out_path, out_size, "%s/%s", cwd, in_path);
        }
    }
}

static void resolve_dirfd_and_path(pid_t pid, long dirfd, const char *raw_path, const char *cwd, char *out_path, size_t out_size) {
    if (raw_path[0] == '/' || dirfd == AT_FDCWD) {
        char full_virtual[1024] = {0};
        resolve_relative_or_absolute(raw_path, cwd, full_virtual, sizeof(full_virtual));
        if (!resolve_vfs_path(full_virtual, out_path, out_size)) {
            strncpy(out_path, full_virtual, out_size - 1);
            out_path[out_size - 1] = '\0';
        }
    } else {
        char proc_link[128];
        char dir_target[1024];
        snprintf(proc_link, sizeof(proc_link), "/proc/%d/fd/%ld", pid, dirfd);
        ssize_t len = readlink(proc_link, dir_target, sizeof(dir_target) - 1);
        if (len > 0) {
            dir_target[len] = '\0';
            char combined[2048];
            if (strcmp(dir_target, "/") == 0) {
                snprintf(combined, sizeof(combined), "/%s", raw_path);
            } else {
                snprintf(combined, sizeof(combined), "%s/%s", dir_target, raw_path);
            }
            if (!resolve_vfs_path(combined, out_path, out_size)) {
                strncpy(out_path, combined, out_size - 1);
                out_path[out_size - 1] = '\0';
            }
        } else {
            char full_virtual[1024] = {0};
            resolve_relative_or_absolute(raw_path, cwd, full_virtual, sizeof(full_virtual));
            if (!resolve_vfs_path(full_virtual, out_path, out_size)) {
                strncpy(out_path, full_virtual, out_size - 1);
                out_path[out_size - 1] = '\0';
            }
        }
    }
}

static int get_child_regs(pid_t child, arch_regs_t *regs) {
    struct iovec iov = { .iov_base = regs, .iov_len = sizeof(*regs) };
    return ptrace(PTRACE_GETREGSET, child, (void*)(uintptr_t)NT_PRSTATUS, &iov);
}

static int set_child_regs(pid_t child, arch_regs_t *regs) {
    struct iovec iov = { .iov_base = regs, .iov_len = sizeof(*regs) };
    return ptrace(PTRACE_SETREGSET, child, (void*)(uintptr_t)NT_PRSTATUS, &iov);
}

static uintptr_t write_to_child_scratch(pid_t child, uintptr_t sp, const char *str, size_t len) {
    uintptr_t scratch_addr = sp - 2048;
    struct iovec local_iov = { .iov_base = (void*)str, .iov_len = len };
    struct iovec remote_iov = { .iov_base = (void*)scratch_addr, .iov_len = len };

    if (process_vm_writev(child, &local_iov, 1, &remote_iov, 1, 0) == (ssize_t)len) {
        return scratch_addr;
    }
    return 0;
}

static void ensure_argv0(pid_t child, uintptr_t argv_ptr, uintptr_t sh_addr, const char *applet_name) {
    if (argv_ptr == 0) return;
    uintptr_t orig_argv0 = 0;
    struct iovec r_l = { .iov_base = &orig_argv0, .iov_len = sizeof(orig_argv0) };
    struct iovec r_r = { .iov_base = (void*)argv_ptr, .iov_len = sizeof(orig_argv0) };
    if (process_vm_readv(child, &r_l, 1, &r_r, 1, 0) <= 0 || orig_argv0 == 0) return;

    size_t alen = strlen(applet_name) + 1;
    uintptr_t scr = write_to_child_scratch(child, sh_addr, applet_name, alen);
    if (scr != 0) {
        struct iovec w_l = { .iov_base = &scr, .iov_len = sizeof(scr) };
        struct iovec w_r = { .iov_base = (void*)argv_ptr, .iov_len = sizeof(scr) };
        process_vm_writev(child, &w_l, 1, &w_r, 1, 0);
    }
}

// Verify that a helper binary really matches the CPU we are running on before we
// hand the shell over to it. BusyBox is shipped as an Android native library
// (libbusybox.so) so it is stored uncompressed and executable, which means the
// build system never checks it; a mislabelled binary only shows up at exec time
// as ENOEXEC, and by then the shell is already gone. Returns 0 when the ELF
// matches the host ABI, -1 otherwise (with the reason logged).
static int verify_native_abi(const char *path) {
    int fd = open(path, O_RDONLY);
    if (fd < 0) {
        SUP_ERROR("cannot open %s: %s", path, strerror(errno));
        return -1;
    }

    unsigned char hdr[EI_NIDENT];
    if (read(fd, hdr, sizeof(hdr)) != (ssize_t)sizeof(hdr) ||
        memcmp(hdr, ELFMAG, SELFMAG) != 0) {
        SUP_ERROR("%s is not an ELF binary", path);
        close(fd);
        return -1;
    }
    close(fd);

#if defined(__aarch64__)
    const int want_class = ELFCLASS64;
    const char *want_name = "AArch64";
#elif defined(__x86_64__)
    const int want_class = ELFCLASS64;
    const char *want_name = "x86-64";
#elif defined(__arm__)
    const int want_class = ELFCLASS32;
    const char *want_name = "ARM";
#else
    const int want_class = ELFCLASS32;
    const char *want_name = "x86";
#endif

    if (hdr[EI_CLASS] != want_class) {
        SUP_ERROR("%s has the wrong ABI: this device is %s (ELF%d) but the binary "
                  "is ELF%d; the shell cannot start", path, want_name, want_class * 32,
                  hdr[EI_CLASS] * 32);
        return -1;
    }
    return 0;
}

int main(int argc, char *argv[]) {
    if (argc < 4) {
        fprintf(stderr, "USAGE: %s <rootfs_dir> <native_lib_dir> <command> [args...]\n", argv[0]);
        return 1;
    }

    const char *rootfs_dir = argv[1];
    const char *native_lib_dir = argv[2];
    const char *command = argv[3];
    char **cmd_argv = &argv[3];

    init_mount_table(rootfs_dir);
    relativize_symlinks(rootfs_dir);

    // Fail loudly and early if the shell binary is present but cannot possibly
    // run here. Doing this before forking keeps the diagnostic out of the
    // guest's terminal. A missing binary is not fatal: the caller has its own
    // fallback chain.
    char busybox_probe[2048];
    snprintf(busybox_probe, sizeof(busybox_probe), "%s/libbusybox.so", native_lib_dir);
    if (access(busybox_probe, R_OK) == 0 && verify_native_abi(busybox_probe) != 0) {
        SUP_ERROR("refusing to start: %s is not executable on this device", busybox_probe);
        return 1;
    }

    pid_t child = fork();
    if (child < 0) return 1;

    if (child == 0) {
        // Child Process: Join a new process group. The controlling terminal is
        // transferred to this group by the supervisor *after* the child stops;
        // calling tcsetpgrp() here would raise SIGTTOU and strand the shell in
        // the background, where its first read() gets SIGTTIN-suspended.
        if (isatty(STDIN_FILENO)) {
            setpgid(0, 0);
        }

        if (ptrace(PTRACE_TRACEME, 0, NULL, NULL) < 0) {
            SUP_ERROR("PTRACE_TRACEME failed: %s", strerror(errno));
            _exit(1);
        }
        raise(SIGSTOP);

        setvbuf(stdout, NULL, _IONBF, 0);
        setvbuf(stderr, NULL, _IONBF, 0);

        execv(command, cmd_argv);
        SUP_ERROR("execv(%s) failed: %s", command, strerror(errno));
        _exit(127);
    }

    // Supervisor Process
    g_child_pgid = child;
    memset(pid_table, 0, sizeof(pid_table));
    get_or_create_pid(child);

    // The supervisor is a background process with respect to the terminal it
    // owns, so any terminal bookkeeping it performs (tcsetpgrp, writing while
    // not the foreground group) would raise SIGTTOU/SIGTTIN and suspend it,
    // which looks exactly like a hung session. Ignore both for our lifetime.
    signal(SIGTTOU, SIG_IGN);
    signal(SIGTTIN, SIG_IGN);

    signal(SIGINT, signal_handler);
    signal(SIGTERM, signal_handler);
    signal(SIGHUP, signal_handler);

    int status;
    if (waitpid(child, &status, 0) < 0) {
        cleanup_process_group();
        return 1;
    }

    // The child is stopped in PTRACE_TRACEME. Only now is it safe for the
    // supervisor to move the controlling terminal to the child's process group:
    // doing it while the child still shares our group would fail.
    establish_terminal_foreground(child);

    unsigned long options = PTRACE_O_TRACESYSGOOD | PTRACE_O_TRACEFORK | 
                            PTRACE_O_TRACEVFORK | PTRACE_O_TRACECLONE | 
                            PTRACE_O_TRACEEXEC;
    ptrace(PTRACE_SETOPTIONS, child, NULL, (void*)options);
    ptrace(PTRACE_SYSCALL, child, NULL, 0);

    while (count_active_pids() > 0) {
        pid_t cur_pid = waitpid(-1, &status, __WALL);
        if (cur_pid < 0) {
            if (errno == ECHILD) break;
            continue;
        }

        pid_state_t *ps = get_or_create_pid(cur_pid);
        if (!ps) continue;

        if (WIFEXITED(status)) {
            SUP_INFO("Child PID %d exited with code %d", cur_pid, WEXITSTATUS(status));
            remove_pid(cur_pid);
            continue;
        }
        if (WIFSIGNALED(status)) {
            int termsig = WTERMSIG(status);
            SUP_ERROR("Child PID %d killed by signal %d (%s)", cur_pid, termsig, strsignal(termsig));
            remove_pid(cur_pid);
            continue;
        }

        if (WIFSTOPPED(status)) {
            int sig = WSTOPSIG(status);
            int event = (status >> 16) & 0xff;

            // Handle Fork / Clone / Exec
            if (event == PTRACE_EVENT_FORK || event == PTRACE_EVENT_VFORK || event == PTRACE_EVENT_CLONE) {
                unsigned long new_child = 0;
                ptrace(PTRACE_GETEVENTMSG, cur_pid, NULL, &new_child);
                if (new_child > 0) {
                    pid_state_t *new_ps = get_or_create_pid((pid_t)new_child);
                    if (new_ps) {
                        strncpy(new_ps->cwd, ps->cwd, sizeof(new_ps->cwd));
                    }
                    SUP_INFO("Attached to child PID=%lu (inherited CWD=%s)", new_child, ps->cwd);
                }
                ptrace(PTRACE_SYSCALL, cur_pid, NULL, 0);
                continue;
            }

            if (event == PTRACE_EVENT_EXEC) {
                // Do NOT reset in_syscall here: the execve syscall-exit stop
                // still follows this event, and clearing the flag would make
                // that exit look like an entry, desynchronising the
                // entry/exit alternation for every later syscall.
                ptrace(PTRACE_SYSCALL, cur_pid, NULL, 0);
                continue;
            }

            // Handle Syscalls
            if (sig == (SIGTRAP | 0x80)) {
                arch_regs_t regs;
                if (get_child_regs(cur_pid, &regs) == 0) {
                    // x86 kernels write -ENOSYS into the return register at
                    // syscall entry, but aarch64/arm do not: x0 holds ARG1 at
                    // entry, so testing it for -ENOSYS is always false and no
                    // path was ever rewritten on arm64 devices. Alternate on
                    // every syscall-stop instead; PTRACE_SYSCALL reports
                    // entry/exit strictly in pairs per thread.
                    int is_entry = !ps->in_syscall;
                    ps->in_syscall = !ps->in_syscall;

                    if (is_entry) {
                        long nr = ARCH_SYSCALL_NR(regs);
                        ps->last_syscall = nr;
                        uintptr_t sp = ARCH_SP(regs);

                        if (nr == __NR_chdir) {
                            uintptr_t path_addr = ARCH_ARG1(regs);
                            if (path_addr != 0) {
                                char raw_path[1024] = {0};
                                struct iovec l_iov = { .iov_base = raw_path, .iov_len = sizeof(raw_path)-1 };
                                struct iovec r_iov = { .iov_base = (void*)path_addr, .iov_len = sizeof(raw_path)-1 };
                                if (process_vm_readv(cur_pid, &l_iov, 1, &r_iov, 1, 0) > 0) {
                                    char full_virtual[1024] = {0};
                                    resolve_relative_or_absolute(raw_path, ps->cwd, full_virtual, sizeof(full_virtual));
                                    strncpy(ps->pending_chdir, full_virtual, sizeof(ps->pending_chdir));

                                    char rew_path[2048] = {0};
                                    if (resolve_vfs_path(full_virtual, rew_path, sizeof(rew_path))) {
                                        uintptr_t scr = write_to_child_scratch(cur_pid, sp, rew_path, strlen(rew_path)+1);
                                        if (scr != 0) {
                                            SET_ARCH_ARG1(regs, scr);
                                            set_child_regs(cur_pid, &regs);
                                        }
                                    }
                                }
                            }
                        } else if (nr == __NR_openat || nr == __NR_newfstatat) {
                            long dirfd = (long)ARCH_ARG1(regs);
                            uintptr_t path_addr = ARCH_ARG2(regs);
                            if (path_addr != 0) {
                                char raw_path[1024] = {0};
                                struct iovec l_iov = { .iov_base = raw_path, .iov_len = sizeof(raw_path)-1 };
                                struct iovec r_iov = { .iov_base = (void*)path_addr, .iov_len = sizeof(raw_path)-1 };
                                if (process_vm_readv(cur_pid, &l_iov, 1, &r_iov, 1, 0) > 0) {
                                    char rew_path[2048] = {0};
                                    resolve_dirfd_and_path(cur_pid, dirfd, raw_path, ps->cwd, rew_path, sizeof(rew_path));

                                    if (strcmp(rew_path, raw_path) != 0) {
                                        uintptr_t scr = write_to_child_scratch(cur_pid, sp, rew_path, strlen(rew_path)+1);
                                        if (scr != 0) {
                                            SET_ARCH_ARG2(regs, scr);
                                            set_child_regs(cur_pid, &regs);
                                            SUP_INFO("PID %d: redirected %s %s -> %s",
                                                     cur_pid, (nr == __NR_openat ? "openat" : "newfstatat"), raw_path, rew_path);
                                        }
                                    }
                                }
                            }
                        } else if (nr == __NR_open || nr == __NR_stat || nr == __NR_lstat) {
                            // Same as openat/newfstatat but with no dirfd: the
                            // path is always resolved against the guest cwd.
                            uintptr_t path_addr = ARCH_ARG1(regs);
                            if (path_addr != 0) {
                                char raw_path[1024] = {0};
                                struct iovec l_iov = { .iov_base = raw_path, .iov_len = sizeof(raw_path)-1 };
                                struct iovec r_iov = { .iov_base = (void*)path_addr, .iov_len = sizeof(raw_path)-1 };
                                if (process_vm_readv(cur_pid, &l_iov, 1, &r_iov, 1, 0) > 0) {
                                    char rew_path[2048] = {0};
                                    resolve_dirfd_and_path(cur_pid, AT_FDCWD, raw_path, ps->cwd, rew_path, sizeof(rew_path));

                                    if (strcmp(rew_path, raw_path) != 0) {
                                        uintptr_t scr = write_to_child_scratch(cur_pid, sp, rew_path, strlen(rew_path)+1);
                                        if (scr != 0) {
                                            SET_ARCH_ARG1(regs, scr);
                                            set_child_regs(cur_pid, &regs);
                                            SUP_INFO("PID %d: redirected path %s -> %s", cur_pid, raw_path, rew_path);
                                        }
                                    }
                                }
                            }
                        } else if (nr == __NR_execve) {
                            uintptr_t path_addr = ARCH_ARG1(regs);
                            if (path_addr != 0) {
                                char raw_path[1024] = {0};
                                struct iovec l_iov = { .iov_base = raw_path, .iov_len = sizeof(raw_path)-1 };
                                struct iovec r_iov = { .iov_base = (void*)path_addr, .iov_len = sizeof(raw_path)-1 };
                                if (process_vm_readv(cur_pid, &l_iov, 1, &r_iov, 1, 0) > 0) {
                                    char full_virtual[1024] = {0};
                                    resolve_relative_or_absolute(raw_path, ps->cwd, full_virtual, sizeof(full_virtual));

                                    if (strncmp(full_virtual, "/bin/", 5) == 0) {
                                        const char *applet = full_virtual + 5;
                                        char busybox_path[2048] = {0};
                                        snprintf(busybox_path, sizeof(busybox_path), "%s/libbusybox.so", native_lib_dir);
                                        uintptr_t scr = write_to_child_scratch(cur_pid, sp, busybox_path, strlen(busybox_path)+1);
                                        if (scr != 0) {
                                            SET_ARCH_ARG1(regs, scr);
                                            ensure_argv0(cur_pid, ARCH_ARG2(regs), scr, applet);
                                            set_child_regs(cur_pid, &regs);
                                            SUP_INFO("PID %d: redirected execve %s -> %s (applet=%s)",
                                                     cur_pid, full_virtual, busybox_path, applet);
                                        }
                                    } else {
                                        char rew_path[2048] = {0};
                                        struct stat st;
                                        int resolved = resolve_vfs_path(full_virtual, rew_path, sizeof(rew_path));
                                        if (resolved && (stat(rew_path, &st) == 0 || lstat(rew_path, &st) == 0)) {
                                            uintptr_t scr = write_to_child_scratch(cur_pid, sp, rew_path, strlen(rew_path)+1);
                                            if (scr != 0) {
                                                SET_ARCH_ARG1(regs, scr);
                                                set_child_regs(cur_pid, &regs);
                                                SUP_INFO("PID %d: redirected execve %s -> %s",
                                                         cur_pid, full_virtual, rew_path);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        // Syscall EXIT
                        if (ps->last_syscall == __NR_chdir && ps->pending_chdir[0] != '\0') {
                            if (ARCH_RET(regs) == 0) {
                                strncpy(ps->cwd, ps->pending_chdir, sizeof(ps->cwd));
                                SUP_INFO("PID %d: chdir ok, CWD=%s", cur_pid, ps->cwd);
                            }
                            ps->pending_chdir[0] = '\0';
                        } else if (ps->last_syscall == __NR_getuid  ||
                                   ps->last_syscall == __NR_geteuid ||
                                   ps->last_syscall == __NR_getgid  ||
                                   ps->last_syscall == __NR_getegid) {
                            // Fake root: report UID/GID 0 so Alpine tools and
                            // package managers that refuse to run as a normal
                            // user work inside the container. The return value
                            // must be written to the *return* register, which
                            // is rax on x86_64 rather than the ARG1 register.
                            SET_ARCH_RET(regs, 0);
                            set_child_regs(cur_pid, &regs);
                            SUP_INFO("PID %d: faked %s -> 0 (root)", cur_pid,
                                     ps->last_syscall == __NR_getuid  ? "getuid"  :
                                     ps->last_syscall == __NR_geteuid ? "geteuid" :
                                     ps->last_syscall == __NR_getgid  ? "getgid"  : "getegid");
                        } else if (ps->last_syscall == __NR_getcwd && ARCH_RET(regs) > 0) {
                            // The kernel writes the *host* path of the working
                            // directory into the caller's buffer, so `pwd`
                            // printed /data/user/0/<pkg>/files/rootfs. Rewrite
                            // the rootfs prefix away to expose the guest "/".
                            uintptr_t buf_addr = ARCH_ARG1(regs);
                            char host_cwd[2048] = {0};
                            if (buf_addr != 0) {
                                struct iovec l_iov = { .iov_base = host_cwd, .iov_len = sizeof(host_cwd) - 1 };
                                struct iovec r_iov = { .iov_base = (void*)buf_addr, .iov_len = sizeof(host_cwd) - 1 };
                                if (process_vm_readv(cur_pid, &l_iov, 1, &r_iov, 1, 0) > 0) {
                                    size_t root_len = strlen(g_rootfs_dir);
                                    if (root_len > 0 && strncmp(host_cwd, g_rootfs_dir, root_len) == 0) {
                                        const char *guest = host_cwd + root_len;
                                        if (guest[0] != '/') guest = "/";
                                        if (strlen(guest) + 1 <= (size_t) ARCH_RET(regs)) {
                                            struct iovec w_l = { .iov_base = (void *)guest, .iov_len = strlen(guest) + 1 };
                                            struct iovec w_r = { .iov_base = (void*)buf_addr, .iov_len = strlen(guest) + 1 };
                                            process_vm_writev(cur_pid, &w_l, 1, &w_r, 1, 0);
                                            SUP_INFO("PID %d: getcwd %s -> %s", cur_pid, host_cwd, guest);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                ptrace(PTRACE_SYSCALL, cur_pid, NULL, 0);
            } else if (sig == SIGTRAP) {
                // A plain trap is not part of the syscall entry/exit pair, so
                // the alternation must be left untouched here for the same
                // reason as PTRACE_EVENT_EXEC above.
                ptrace(PTRACE_SYSCALL, cur_pid, NULL, 0);
            } else if (sig == SIGSTOP) {
                ptrace(PTRACE_SETOPTIONS, cur_pid, NULL, (void*)options);
                ptrace(PTRACE_SYSCALL, cur_pid, NULL, 0);
            } else if (sig == SIGTTIN || sig == SIGTTOU) {
                // Do NOT suspend or kill interactive shell on TTY control signals
                ptrace(PTRACE_SYSCALL, cur_pid, NULL, 0);
            } else {
                ptrace(PTRACE_SYSCALL, cur_pid, NULL, (void*)(uintptr_t)sig);
            }
        }
    }

    cleanup_process_group();
    restore_terminal_foreground();
    return 0;
}
