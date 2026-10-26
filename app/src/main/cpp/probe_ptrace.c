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

#ifndef __NR_chdir
#if defined(__x86_64__)
#define __NR_chdir 80
#elif defined(__aarch64__)
#define __NR_chdir 49
#elif defined(__i386__) || defined(__arm__)
#define __NR_chdir 12
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
#elif defined(__aarch64__)
typedef struct user_pt_regs arch_regs_t;
#define ARCH_SP(regs) ((uintptr_t)(regs).sp)
#define ARCH_SYSCALL_NR(regs) ((long)(regs).regs[8])
#define ARCH_RET(regs) ((long)(regs).regs[0])
#define ARCH_ARG1(regs) ((uintptr_t)(regs).regs[0])
#define ARCH_ARG2(regs) ((uintptr_t)(regs).regs[1])
#define SET_ARCH_ARG1(regs, val) ((regs).regs[0] = (__u64)(val))
#define SET_ARCH_ARG2(regs, val) ((regs).regs[1] = (__u64)(val))
#elif defined(__arm__)
typedef struct user_regs arch_regs_t;
#define ARCH_SP(regs) ((uintptr_t)(regs).uregs[13])
#define ARCH_SYSCALL_NR(regs) ((long)(regs).uregs[7])
#define ARCH_RET(regs) ((long)(regs).uregs[0])
#define ARCH_ARG1(regs) ((uintptr_t)(regs).uregs[0])
#define ARCH_ARG2(regs) ((uintptr_t)(regs).uregs[1])
#define SET_ARCH_ARG1(regs, val) ((regs).uregs[0] = (unsigned long)(val))
#define SET_ARCH_ARG2(regs, val) ((regs).uregs[1] = (unsigned long)(val))
#else
typedef struct user_regs_struct arch_regs_t;
#define ARCH_SP(regs) ((uintptr_t)(regs).esp)
#define ARCH_SYSCALL_NR(regs) ((long)(regs).orig_eax)
#define ARCH_RET(regs) ((long)(regs).eax)
#define ARCH_ARG1(regs) ((uintptr_t)(regs).ebx)
#define ARCH_ARG2(regs) ((uintptr_t)(regs).ecx)
#define SET_ARCH_ARG1(regs, val) ((regs).ebx = (unsigned long)(val))
#define SET_ARCH_ARG2(regs, val) ((regs).ecx = (unsigned long)(val))
#endif

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

static void signal_handler(int sig) {
    cleanup_process_group();
    _exit(128 + sig);
}

// ---------------- Generic Mount Table ----------------
typedef struct {
    const char *guest_prefix;
    char host_target[1024];
} mount_entry_t;

static mount_entry_t mount_table[4];

static void init_mount_table(const char *rootfs_dir) {
    mount_table[0].guest_prefix = "/bin";
    snprintf(mount_table[0].host_target, sizeof(mount_table[0].host_target), "%s/bin", rootfs_dir);

    mount_table[1].guest_prefix = "/etc";
    snprintf(mount_table[1].host_target, sizeof(mount_table[1].host_target), "%s/etc", rootfs_dir);

    mount_table[2].guest_prefix = "/lib64";
    snprintf(mount_table[2].host_target, sizeof(mount_table[2].host_target), "%s/lib64", rootfs_dir);

    mount_table[3].guest_prefix = "/lib";
    snprintf(mount_table[3].host_target, sizeof(mount_table[3].host_target), "%s/lib", rootfs_dir);
}

static int resolve_vfs_path(const char *in_path, char *out_path, size_t out_size) {
    for (int i = 0; i < 4; i++) {
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

// Helper: resolve relative path against process CWD
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

// ---------------- Memory & Reg Helpers ----------------
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

// ---------------- Main Supervisor Logic ----------------
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

    pid_t child = fork();
    if (child < 0) return 1;

    if (child == 0) {
        // Child Process
        setpgid(0, 0);

        if (ptrace(PTRACE_TRACEME, 0, NULL, NULL) < 0) {
            perror("ptrace PTRACE_TRACEME failed");
            exit(1);
        }
        raise(SIGSTOP);

        setvbuf(stdout, NULL, _IONBF, 0);
        setvbuf(stderr, NULL, _IONBF, 0);

        execv(command, cmd_argv);
        perror("execv failed in child");
        _exit(127);
    }

    // Supervisor Process
    g_child_pgid = child;
    memset(pid_table, 0, sizeof(pid_table));
    get_or_create_pid(child);

    signal(SIGINT, signal_handler);
    signal(SIGTERM, signal_handler);
    signal(SIGHUP, signal_handler);

    int status;
    if (waitpid(child, &status, 0) < 0) {
        cleanup_process_group();
        return 1;
    }

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

            // Handle Fork / Clone / Exec
            if (event == PTRACE_EVENT_FORK || event == PTRACE_EVENT_VFORK || event == PTRACE_EVENT_CLONE) {
                unsigned long new_child = 0;
                ptrace(PTRACE_GETEVENTMSG, cur_pid, NULL, &new_child);
                if (new_child > 0) {
                    pid_state_t *new_ps = get_or_create_pid((pid_t)new_child);
                    if (new_ps) {
                        strncpy(new_ps->cwd, ps->cwd, sizeof(new_ps->cwd));
                    }
                    printf("SUPERVISOR: Attached to child PID=%lu (inherited CWD=%s)\n", new_child, ps->cwd);
                    fflush(stdout);
                }
                ptrace(PTRACE_SYSCALL, cur_pid, NULL, 0);
                continue;
            }

            if (event == PTRACE_EVENT_EXEC) {
                ps->in_syscall = 0;
                ptrace(PTRACE_SYSCALL, cur_pid, NULL, 0);
                continue;
            }

            // Handle Syscalls
            if (sig == (SIGTRAP | 0x80)) {
                arch_regs_t regs;
                if (get_child_regs(cur_pid, &regs) == 0) {
                    int is_entry = (ARCH_RET(regs) == -ENOSYS);

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
                            uintptr_t path_addr = ARCH_ARG2(regs);
                            if (path_addr != 0) {
                                char raw_path[1024] = {0};
                                struct iovec l_iov = { .iov_base = raw_path, .iov_len = sizeof(raw_path)-1 };
                                struct iovec r_iov = { .iov_base = (void*)path_addr, .iov_len = sizeof(raw_path)-1 };
                                if (process_vm_readv(cur_pid, &l_iov, 1, &r_iov, 1, 0) > 0) {
                                    char full_virtual[1024] = {0};
                                    resolve_relative_or_absolute(raw_path, ps->cwd, full_virtual, sizeof(full_virtual));

                                    char rew_path[2048] = {0};
                                    if (resolve_vfs_path(full_virtual, rew_path, sizeof(rew_path))) {
                                        uintptr_t scr = write_to_child_scratch(cur_pid, sp, rew_path, strlen(rew_path)+1);
                                        if (scr != 0) {
                                            SET_ARCH_ARG2(regs, scr);
                                            set_child_regs(cur_pid, &regs);
                                            printf("SUPERVISOR [PID=%d]: Redirected %s %s -> %s\n",
                                                   cur_pid, (nr == __NR_openat ? "openat" : "newfstatat"), full_virtual, rew_path);
                                            fflush(stdout);
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

                                    char rew_path[2048] = {0};
                                    struct stat st;
                                    int resolved = resolve_vfs_path(full_virtual, rew_path, sizeof(rew_path));
                                    if (resolved && (stat(rew_path, &st) == 0 || lstat(rew_path, &st) == 0)) {
                                        uintptr_t scr = write_to_child_scratch(cur_pid, sp, rew_path, strlen(rew_path)+1);
                                        if (scr != 0) {
                                            SET_ARCH_ARG1(regs, scr);
                                            set_child_regs(cur_pid, &regs);
                                            printf("SUPERVISOR [PID=%d]: Redirected execve %s -> %s\n",
                                                   cur_pid, full_virtual, rew_path);
                                            fflush(stdout);
                                        }
                                    } else if (strncmp(full_virtual, "/bin/", 5) == 0) {
                                        const char *applet = full_virtual + 5;
                                        char busybox_path[2048] = {0};
                                        snprintf(busybox_path, sizeof(busybox_path), "%s/libbusybox.so", native_lib_dir);
                                        uintptr_t scr = write_to_child_scratch(cur_pid, sp, busybox_path, strlen(busybox_path)+1);
                                        if (scr != 0) {
                                            SET_ARCH_ARG1(regs, scr);
                                            ensure_argv0(cur_pid, ARCH_ARG2(regs), scr, applet);
                                            set_child_regs(cur_pid, &regs);
                                            printf("SUPERVISOR [PID=%d]: Redirected execve %s -> %s (applet=%s)\n",
                                                   cur_pid, full_virtual, busybox_path, applet);
                                            fflush(stdout);
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
                                printf("SUPERVISOR [PID=%d]: chdir successful, updated CWD=%s\n", cur_pid, ps->cwd);
                                fflush(stdout);
                            }
                            ps->pending_chdir[0] = '\0';
                        }
                    }
                }
                ptrace(PTRACE_SYSCALL, cur_pid, NULL, 0);
            } else if (sig == SIGTRAP) {
                ps->in_syscall = 0;
                ptrace(PTRACE_SYSCALL, cur_pid, NULL, 0);
            } else if (sig == SIGSTOP) {
                ptrace(PTRACE_SETOPTIONS, cur_pid, NULL, (void*)options);
                ptrace(PTRACE_SYSCALL, cur_pid, NULL, 0);
            } else {
                ptrace(PTRACE_SYSCALL, cur_pid, NULL, (void*)(uintptr_t)sig);
            }
        }
    }

    cleanup_process_group();
    fflush(stdout);
    return 0;
}
