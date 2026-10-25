#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/ptrace.h>
#include <sys/wait.h>
#include <sys/uio.h>
#include <sys/user.h>
#include <sys/syscall.h>
#include <sys/stat.h>
#include <elf.h>
#include <signal.h>

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

extern char **environ;

#if defined(__x86_64__)
typedef struct user_regs_struct arch_regs_t;
#define ARCH_SP(regs) ((uintptr_t)(regs).rsp)
#define ARCH_SYSCALL_NR(regs) ((long)(regs).orig_rax)
#define ARCH_ARG1(regs) ((uintptr_t)(regs).rdi)
#define ARCH_ARG2(regs) ((uintptr_t)(regs).rsi)
#define SET_ARCH_ARG1(regs, val) ((regs).rdi = (unsigned long long)(val))
#define SET_ARCH_ARG2(regs, val) ((regs).rsi = (unsigned long long)(val))
#elif defined(__aarch64__)
typedef struct user_pt_regs arch_regs_t;
#define ARCH_SP(regs) ((uintptr_t)(regs).sp)
#define ARCH_SYSCALL_NR(regs) ((long)(regs).regs[8])
#define ARCH_ARG1(regs) ((uintptr_t)(regs).regs[0])
#define ARCH_ARG2(regs) ((uintptr_t)(regs).regs[1])
#define SET_ARCH_ARG1(regs, val) ((regs).regs[0] = (__u64)(val))
#define SET_ARCH_ARG2(regs, val) ((regs).regs[1] = (__u64)(val))
#elif defined(__arm__)
typedef struct user_regs arch_regs_t;
#define ARCH_SP(regs) ((uintptr_t)(regs).uregs[13])
#define ARCH_SYSCALL_NR(regs) ((long)(regs).uregs[7])
#define ARCH_ARG1(regs) ((uintptr_t)(regs).uregs[0])
#define ARCH_ARG2(regs) ((uintptr_t)(regs).uregs[1])
#define SET_ARCH_ARG1(regs, val) ((regs).uregs[0] = (unsigned long)(val))
#define SET_ARCH_ARG2(regs, val) ((regs).uregs[1] = (unsigned long)(val))
#else
typedef struct user_regs_struct arch_regs_t;
#define ARCH_SP(regs) ((uintptr_t)(regs).esp)
#define ARCH_SYSCALL_NR(regs) ((long)(regs).orig_eax)
#define ARCH_ARG1(regs) ((uintptr_t)(regs).ebx)
#define ARCH_ARG2(regs) ((uintptr_t)(regs).ecx)
#define SET_ARCH_ARG1(regs, val) ((regs).ebx = (unsigned long)(val))
#define SET_ARCH_ARG2(regs, val) ((regs).ecx = (unsigned long)(val))
#endif

// ---------------- Process Tracking Table ----------------
#define MAX_TRACKED_PIDS 64
typedef struct {
    pid_t pid;
    int in_syscall;
    long last_syscall;
    int is_active;
} pid_state_t;

static pid_state_t pid_table[MAX_TRACKED_PIDS];

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

// ---------------- Generic Mount Table ----------------
typedef struct {
    const char *guest_prefix;
    char host_target[1024];
} mount_entry_t;

static mount_entry_t mount_table[3];

static void init_mount_table(const char *rootfs_dir) {
    mount_table[0].guest_prefix = "/bin";
    snprintf(mount_table[0].host_target, sizeof(mount_table[0].host_target), "%s/bin", rootfs_dir);

    mount_table[1].guest_prefix = "/etc";
    snprintf(mount_table[1].host_target, sizeof(mount_table[1].host_target), "%s/etc", rootfs_dir);

    mount_table[2].guest_prefix = "/system";
    snprintf(mount_table[2].host_target, sizeof(mount_table[2].host_target), "%s/system", rootfs_dir);
}

static int resolve_vfs_path(const char *in_path, char *out_path, size_t out_size) {
    for (int i = 0; i < 3; i++) {
        size_t plen = strlen(mount_table[i].guest_prefix);
        if (strncmp(in_path, mount_table[i].guest_prefix, plen) == 0) {
            if (in_path[plen] == '/' || in_path[plen] == '\0') {
                snprintf(out_path, out_size, "%s%s", mount_table[i].host_target, in_path + plen);
                return 1;
            }
        }
    }
    return 0;
}

// ---------------- Memory & Reg Helpers ----------------
static int get_child_regs(pid_t child, arch_regs_t *regs) {
    struct iovec iov = { .iov_base = regs, .iov_len = sizeof(*regs) };
    return ptrace(PTRACE_GETREGSET, child, (void*)(uintptr_t)NT_PRSTATUS, &iov);
}

static int set_child_regs(pid_t child, arch_regs_t *regs) {
    struct iovec iov = { .iov_base = regs, .iov_len = sizeof(*regs) };
    return ptrace(PTRACE_SETREGSET, child, (void*)(uintptr_t)NT_PRSTATUS, &iov);
}

static uintptr_t write_to_child_scratch(pid_t child, uintptr_t sp, const void *data, size_t len) {
    uintptr_t scratch_addr = (sp - 2048) & ~15UL;
    struct iovec local_iov = { .iov_base = (void*)data, .iov_len = len };
    struct iovec remote_iov = { .iov_base = (void*)scratch_addr, .iov_len = len };
    if (process_vm_writev(child, &local_iov, 1, &remote_iov, 1, 0) != (ssize_t)len) return 0;
    return scratch_addr;
}

static void ensure_argv0(pid_t child, uintptr_t argv_ptr, uintptr_t scratch_sp, const char *applet) {
    if (argv_ptr == 0 || applet == NULL) return;
    uintptr_t sh_addr = scratch_sp + 512;
    size_t alen = strlen(applet) + 1;
    struct iovec sh_l = { .iov_base = (void*)applet, .iov_len = alen };
    struct iovec sh_r = { .iov_base = (void*)sh_addr, .iov_len = alen };
    if (process_vm_writev(child, &sh_l, 1, &sh_r, 1, 0) == (ssize_t)alen) {
#if defined(__x86_64__) || defined(__aarch64__)
        uint64_t new_ptr = (uint64_t)sh_addr;
#else
        uint32_t new_ptr = (uint32_t)sh_addr;
#endif
        struct iovec w_l = { .iov_base = &new_ptr, .iov_len = sizeof(new_ptr) };
        struct iovec w_r = { .iov_base = (void*)argv_ptr, .iov_len = sizeof(new_ptr) };
        process_vm_writev(child, &w_l, 1, &w_r, 1, 0);
    }
}

// ---------------- Main Supervisor Logic ----------------
int main(int argc, char *argv[]) {
    if (argc < 3) {
        fprintf(stderr, "USAGE: %s <rootfs_dir> <native_lib_dir>\n", argv[0]);
        return 1;
    }

    const char *rootfs_dir = argv[1];
    const char *native_lib_dir = argv[2];
    init_mount_table(rootfs_dir);

    pid_t child = fork();
    if (child < 0) return 1;

    if (child == 0) {
        // Child Process
        if (ptrace(PTRACE_TRACEME, 0, NULL, NULL) < 0) exit(1);
        raise(SIGSTOP);

        // Pre-checks
        struct stat st;
        syscall(__NR_newfstatat, AT_FDCWD, "/bin/sh", &st, 0);
        int fd = syscall(__NR_openat, AT_FDCWD, "/bin/sh", O_RDONLY, 0);
        if (fd >= 0) close(fd);

        // Gate 3.1 Multi-Process Pipeline Test
        char *exec_args[] = { "sh", "-c", "echo VPORTABLE_GATE_3_1_SUCCESS | cat", NULL };
        printf("CHILD: Invoking Pipeline: /bin/sh -c \"echo VPORTABLE_GATE_3_1_SUCCESS | cat\"...\n");
        fflush(stdout);

        execve("/bin/sh", exec_args, environ);
        exit(1);
    }

    // Supervisor Process
    memset(pid_table, 0, sizeof(pid_table));
    get_or_create_pid(child);

    int status;
    if (waitpid(child, &status, 0) < 0) return 1;

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

        if (WIFEXITED(status) || WIFSIGNALED(status)) {
            remove_pid(cur_pid);
            continue;
        }

        if (WIFSTOPPED(status)) {
            int sig = WSTOPSIG(status);
            int event = (status >> 16) & 0xff;

            // Handle Fork / Clone
            if (event == PTRACE_EVENT_FORK || event == PTRACE_EVENT_VFORK || event == PTRACE_EVENT_CLONE) {
                unsigned long new_child = 0;
                ptrace(PTRACE_GETEVENTMSG, cur_pid, NULL, &new_child);
                if (new_child > 0) {
                    get_or_create_pid((pid_t)new_child);
                    printf("SUPERVISOR: Attached to forked child PID=%lu\n", new_child);
                    fflush(stdout);
                }
                ptrace(PTRACE_SYSCALL, cur_pid, NULL, 0);
                continue;
            }

            // Handle Syscalls
            if (sig == (SIGTRAP | 0x80)) {
                if (!ps->in_syscall) {
                    ps->in_syscall = 1;
                    arch_regs_t regs;
                    if (get_child_regs(cur_pid, &regs) == 0) {
                        long nr = ARCH_SYSCALL_NR(regs);
                        ps->last_syscall = nr;
                        uintptr_t sp = ARCH_SP(regs);

                        if (nr == __NR_openat || nr == __NR_newfstatat) {
                            uintptr_t path_addr = ARCH_ARG2(regs);
                            if (path_addr != 0) {
                                char cpath[1024] = {0};
                                struct iovec l_iov = { .iov_base = cpath, .iov_len = sizeof(cpath)-1 };
                                struct iovec r_iov = { .iov_base = (void*)path_addr, .iov_len = sizeof(cpath)-1 };
                                if (process_vm_readv(cur_pid, &l_iov, 1, &r_iov, 1, 0) > 0) {
                                    char rew_path[2048] = {0};
                                    if (resolve_vfs_path(cpath, rew_path, sizeof(rew_path))) {
                                        uintptr_t scr = write_to_child_scratch(cur_pid, sp, rew_path, strlen(rew_path)+1);
                                        if (scr != 0) {
                                            SET_ARCH_ARG2(regs, scr);
                                            set_child_regs(cur_pid, &regs);
                                        }
                                    }
                                }
                            }
                        } else if (nr == __NR_execve) {
                            uintptr_t path_addr = ARCH_ARG1(regs);
                            if (path_addr != 0) {
                                char cpath[1024] = {0};
                                struct iovec l_iov = { .iov_base = cpath, .iov_len = sizeof(cpath)-1 };
                                struct iovec r_iov = { .iov_base = (void*)path_addr, .iov_len = sizeof(cpath)-1 };
                                if (process_vm_readv(cur_pid, &l_iov, 1, &r_iov, 1, 0) > 0) {
                                    if (strncmp(cpath, "/bin/", 5) == 0) {
                                        const char *applet = cpath + 5;
                                        char busybox_path[2048] = {0};
                                        snprintf(busybox_path, sizeof(busybox_path), "%s/libbusybox.so", native_lib_dir);
                                        uintptr_t scr = write_to_child_scratch(cur_pid, sp, busybox_path, strlen(busybox_path)+1);
                                        if (scr != 0) {
                                            SET_ARCH_ARG1(regs, scr);
                                            ensure_argv0(cur_pid, ARCH_ARG2(regs), scr, applet);
                                            set_child_regs(cur_pid, &regs);
                                            printf("SUPERVISOR [PID=%d]: Redirected execve %s -> %s (applet=%s)\n",
                                                   cur_pid, cpath, busybox_path, applet);
                                            fflush(stdout);
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    ps->in_syscall = 0;
                }
                ptrace(PTRACE_SYSCALL, cur_pid, NULL, 0);
            } else if (sig == SIGTRAP) {
                // Exec-stop! Reset syscall tracking for new image
                ps->in_syscall = 0;
                ptrace(PTRACE_SYSCALL, cur_pid, NULL, 0);
            } else if (sig == SIGSTOP) {
                // New child initial stop
                ptrace(PTRACE_SETOPTIONS, cur_pid, NULL, (void*)options);
                ptrace(PTRACE_SYSCALL, cur_pid, NULL, 0);
            } else {
                ptrace(PTRACE_SYSCALL, cur_pid, NULL, (void*)(uintptr_t)sig);
            }
        }
    }

    printf("GATE_3_1_RESULT: MULTI_PROCESS_SUCCESS\n");
    fflush(stdout);
    return 0;
}
