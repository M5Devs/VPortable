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

#define FAKE_PATH "/system/vportable_fake_entry.txt"

extern char **environ;

#if defined(__x86_64__)
typedef struct user_regs_struct arch_regs_t;
#define ARCH_SP(regs) ((uintptr_t)(regs).rsp)
#define ARCH_SYSCALL_NR(regs) ((long)(regs).orig_rax)
#define ARCH_ARG1(regs) ((uintptr_t)(regs).rdi)
#define ARCH_ARG2(regs) ((uintptr_t)(regs).rsi)
#define ARCH_ARG3(regs) ((uintptr_t)(regs).rdx)

#define SET_ARCH_ARG1(regs, val) ((regs).rdi = (unsigned long long)(val))
#define SET_ARCH_ARG2(regs, val) ((regs).rsi = (unsigned long long)(val))
#define SET_ARCH_ARG3(regs, val) ((regs).rdx = (unsigned long long)(val))

#elif defined(__aarch64__)
typedef struct user_pt_regs arch_regs_t;
#define ARCH_SP(regs) ((uintptr_t)(regs).sp)
#define ARCH_SYSCALL_NR(regs) ((long)(regs).regs[8])
#define ARCH_ARG1(regs) ((uintptr_t)(regs).regs[0])
#define ARCH_ARG2(regs) ((uintptr_t)(regs).regs[1])
#define ARCH_ARG3(regs) ((uintptr_t)(regs).regs[2])

#define SET_ARCH_ARG1(regs, val) ((regs).regs[0] = (__u64)(val))
#define SET_ARCH_ARG2(regs, val) ((regs).regs[1] = (__u64)(val))
#define SET_ARCH_ARG3(regs, val) ((regs).regs[2] = (__u64)(val))

#elif defined(__arm__)
typedef struct user_regs arch_regs_t;
#define ARCH_SP(regs) ((uintptr_t)(regs).uregs[13])
#define ARCH_SYSCALL_NR(regs) ((long)(regs).uregs[7])
#define ARCH_ARG1(regs) ((uintptr_t)(regs).uregs[0])
#define ARCH_ARG2(regs) ((uintptr_t)(regs).uregs[1])
#define ARCH_ARG3(regs) ((uintptr_t)(regs).uregs[2])

#define SET_ARCH_ARG1(regs, val) ((regs).uregs[0] = (unsigned long)(val))
#define SET_ARCH_ARG2(regs, val) ((regs).uregs[1] = (unsigned long)(val))
#define SET_ARCH_ARG3(regs, val) ((regs).uregs[2] = (unsigned long)(val))

#elif defined(__i386__)
typedef struct user_regs_struct arch_regs_t;
#define ARCH_SP(regs) ((uintptr_t)(regs).esp)
#define ARCH_SYSCALL_NR(regs) ((long)(regs).orig_eax)
#define ARCH_ARG1(regs) ((uintptr_t)(regs).ebx)
#define ARCH_ARG2(regs) ((uintptr_t)(regs).ecx)
#define ARCH_ARG3(regs) ((uintptr_t)(regs).edx)

#define SET_ARCH_ARG1(regs, val) ((regs).ebx = (unsigned long)(val))
#define SET_ARCH_ARG2(regs, val) ((regs).ecx = (unsigned long)(val))
#define SET_ARCH_ARG3(regs, val) ((regs).edx = (unsigned long)(val))

#else
#error "Unsupported architecture"
#endif

static int get_child_regs(pid_t child, arch_regs_t *regs) {
    struct iovec iov = {
        .iov_base = regs,
        .iov_len = sizeof(*regs)
    };
    return ptrace(PTRACE_GETREGSET, child, (void*)(uintptr_t)NT_PRSTATUS, &iov);
}

static int set_child_regs(pid_t child, arch_regs_t *regs) {
    struct iovec iov = {
        .iov_base = regs,
        .iov_len = sizeof(*regs)
    };
    return ptrace(PTRACE_SETREGSET, child, (void*)(uintptr_t)NT_PRSTATUS, &iov);
}

static uintptr_t write_to_child_scratch(pid_t child, uintptr_t sp, const void *data, size_t len) {
    uintptr_t scratch_addr = (sp - 2048) & ~15UL;
    struct iovec local_iov = { .iov_base = (void*)data, .iov_len = len };
    struct iovec remote_iov = { .iov_base = (void*)scratch_addr, .iov_len = len };
    ssize_t nwritten = process_vm_writev(child, &local_iov, 1, &remote_iov, 1, 0);
    if (nwritten != (ssize_t)len) {
        return 0;
    }
    return scratch_addr;
}

static void ensure_argv0_is_sh(pid_t child, uintptr_t argv_ptr, uintptr_t scratch_sp) {
    if (argv_ptr == 0) return;

    uintptr_t argv0_addr = 0;
#if defined(__x86_64__) || defined(__aarch64__)
    uint64_t ptr_val = 0;
    struct iovec l_iov = { .iov_base = &ptr_val, .iov_len = sizeof(ptr_val) };
    struct iovec r_iov = { .iov_base = (void*)argv_ptr, .iov_len = sizeof(ptr_val) };
    if (process_vm_readv(child, &l_iov, 1, &r_iov, 1, 0) != sizeof(ptr_val)) return;
    argv0_addr = (uintptr_t)ptr_val;
#else
    uint32_t ptr_val = 0;
    struct iovec l_iov = { .iov_base = &ptr_val, .iov_len = sizeof(ptr_val) };
    struct iovec r_iov = { .iov_base = (void*)argv_ptr, .iov_len = sizeof(ptr_val) };
    if (process_vm_readv(child, &l_iov, 1, &r_iov, 1, 0) != sizeof(ptr_val)) return;
    argv0_addr = (uintptr_t)ptr_val;
#endif

    char argv0_buf[64] = {0};
    if (argv0_addr != 0) {
        struct iovec str_l = { .iov_base = argv0_buf, .iov_len = sizeof(argv0_buf) - 1 };
        struct iovec str_r = { .iov_base = (void*)argv0_addr, .iov_len = sizeof(argv0_buf) - 1 };
        process_vm_readv(child, &str_l, 1, &str_r, 1, 0);
    }

    if (strcmp(argv0_buf, "sh") != 0) {
        uintptr_t sh_addr = scratch_sp + 512;
        struct iovec sh_l = { .iov_base = "sh", .iov_len = 3 };
        struct iovec sh_r = { .iov_base = (void*)sh_addr, .iov_len = 3 };
        if (process_vm_writev(child, &sh_l, 1, &sh_r, 1, 0) == 3) {
#if defined(__x86_64__) || defined(__aarch64__)
            uint64_t new_ptr = (uint64_t)sh_addr;
            struct iovec w_l = { .iov_base = &new_ptr, .iov_len = sizeof(new_ptr) };
            struct iovec w_r = { .iov_base = (void*)argv_ptr, .iov_len = sizeof(new_ptr) };
            process_vm_writev(child, &w_l, 1, &w_r, 1, 0);
#else
            uint32_t new_ptr = (uint32_t)sh_addr;
            struct iovec w_l = { .iov_base = &new_ptr, .iov_len = sizeof(new_ptr) };
            struct iovec w_r = { .iov_base = (void*)argv_ptr, .iov_len = sizeof(new_ptr) };
            process_vm_writev(child, &w_l, 1, &w_r, 1, 0);
#endif
        }
    }
}

int main(int argc, char *argv[]) {
    if (argc < 2 || argv[1] == NULL || argv[1][0] == '\0') {
        fprintf(stderr, "USAGE: %s <rootfs_base_path/target_file_path> [native_lib_dir]\n", argv[0]);
        return 1;
    }

    int is_gate2 = (argc >= 3 && argv[2] != NULL && argv[2][0] != '\0');
    const char *param1 = argv[1];
    const char *param2 = is_gate2 ? argv[2] : NULL;

    if (!is_gate2) {
        if (access(param1, R_OK) != 0) {
            fprintf(stderr, "SUPERVISOR_ERROR: Target file '%s' is not accessible: errno=%d (%s)\n",
                    param1, errno, strerror(errno));
            return 1;
        }
    }

    pid_t child = fork();
    if (child < 0) {
        fprintf(stderr, "FORK_FAILED errno=%d (%s)\n", errno, strerror(errno));
        return 1;
    }

    if (child == 0) {
        // Child Process
        if (ptrace(PTRACE_TRACEME, 0, NULL, NULL) < 0) {
            fprintf(stderr, "PTRACE_TRACEME_FAIL errno=%d (%s)\n", errno, strerror(errno));
            exit(1);
        }

        raise(SIGSTOP);

        if (!is_gate2) {
            // Gate 1.5 single openat test
            char path_buf[512] = FAKE_PATH;
            int fd = openat(AT_FDCWD, path_buf, O_RDONLY);
            if (fd >= 0) {
                char read_buf[256] = {0};
                ssize_t bytes = read(fd, read_buf, sizeof(read_buf) - 1);
                if (bytes >= 0) {
                    read_buf[bytes] = '\0';
                    printf("CHILD_READ: %s\n", read_buf);
                    printf("GATE_1_5_RESULT: VFS_REWRITE_SUCCESS\n");
                    fflush(stdout);
                } else {
                    printf("CHILD_READ_FAILED errno=%d (%s)\n", errno, strerror(errno));
                    fflush(stdout);
                }
                close(fd);
                exit(0);
            } else {
                printf("CHILD_OPEN_FAILED errno=%d (%s)\n", errno, strerror(errno));
                fflush(stdout);
                exit(1);
            }
        } else {
            // Gate 2 test sequence: fstatat, openat, execve
            struct stat st;
            int fstat_res = syscall(__NR_newfstatat, AT_FDCWD, "/bin/sh", &st, 0);
            printf("CHILD: fstatat(\"/bin/sh\") res=%d\n", fstat_res);
            fflush(stdout);

            int fd = syscall(__NR_openat, AT_FDCWD, "/bin/sh", O_RDONLY, 0);
            printf("CHILD: openat(\"/bin/sh\") fd=%d\n", fd);
            fflush(stdout);
            if (fd >= 0) {
                close(fd);
            }

            char *exec_args[] = { "sh", "-c", "echo VPORTABLE_GATE_2_SUCCESS", NULL };
            printf("CHILD: Invoking /bin/sh -c \"echo VPORTABLE_GATE_2_SUCCESS\"...\n");
            fflush(stdout);

            execve("/bin/sh", exec_args, environ);

            fprintf(stderr, "CHILD_EXECVE_FAILED errno=%d (%s)\n", errno, strerror(errno));
            exit(1);
        }
    } else {
        // Supervisor (Parent) Process
        int status;
        if (waitpid(child, &status, 0) < 0) {
            perror("waitpid initial");
            return 1;
        }

        if (!WIFSTOPPED(status)) {
            fprintf(stderr, "CHILD_NOT_STOPPED status=%d\n", status);
            return 1;
        }

        if (ptrace(PTRACE_SETOPTIONS, child, NULL, (void*)(uintptr_t)PTRACE_O_TRACESYSGOOD) < 0) {
            perror("ptrace SETOPTIONS");
            return 1;
        }

        int in_syscall = 0;
        int next_sig = 0;

        while (1) {
            if (ptrace(PTRACE_SYSCALL, child, NULL, (void*)(uintptr_t)next_sig) < 0) {
                if (errno == ESRCH) {
                    break;
                }
                perror("ptrace SYSCALL");
                return 1;
            }
            next_sig = 0;

            if (waitpid(child, &status, 0) < 0) {
                perror("waitpid loop");
                return 1;
            }

            if (WIFEXITED(status)) {
                return WEXITSTATUS(status);
            }
            if (WIFSIGNALED(status)) {
                return 1;
            }

            if (WIFSTOPPED(status)) {
                int sig = WSTOPSIG(status);
                if (sig == (SIGTRAP | 0x80)) {
                    if (!in_syscall) {
                        in_syscall = 1;
                        arch_regs_t regs;
                        if (get_child_regs(child, &regs) == 0) {
                            long syscall_nr = ARCH_SYSCALL_NR(regs);
                            uintptr_t sp = ARCH_SP(regs);

                            if (!is_gate2) {
                                // Gate 1.5 mode
                                if (syscall_nr == __NR_openat) {
                                    uintptr_t path_addr = ARCH_ARG2(regs);
                                    if (path_addr != 0) {
                                        char child_path[512] = {0};
                                        struct iovec local_iov = {
                                            .iov_base = child_path,
                                            .iov_len = sizeof(child_path) - 1
                                        };
                                        struct iovec remote_iov = {
                                            .iov_base = (void*)path_addr,
                                            .iov_len = sizeof(child_path) - 1
                                        };

                                        ssize_t nread = process_vm_readv(child, &local_iov, 1, &remote_iov, 1, 0);
                                        if (nread > 0) {
                                            child_path[nread] = '\0';
                                            if (strcmp(child_path, FAKE_PATH) == 0) {
                                                size_t real_path_len = strlen(param1) + 1;
                                                struct iovec w_local = {
                                                    .iov_base = (void*)param1,
                                                    .iov_len = real_path_len
                                                };
                                                struct iovec w_remote = {
                                                    .iov_base = (void*)path_addr,
                                                    .iov_len = real_path_len
                                                };

                                                ssize_t nwritten = process_vm_writev(child, &w_local, 1, &w_remote, 1, 0);
                                                if (nwritten == (ssize_t)real_path_len) {
                                                    printf("SUPERVISOR: Intercepted %s -> Rewrote to %s\n", FAKE_PATH, param1);
                                                    fflush(stdout);
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                // Gate 2 mode
                                const char *rootfs_dir = param1;
                                const char *native_lib_dir = param2;

                                if (syscall_nr == __NR_openat || syscall_nr == __NR_newfstatat) {
                                    uintptr_t path_addr = ARCH_ARG2(regs);
                                    if (path_addr != 0) {
                                        char child_path[1024] = {0};
                                        struct iovec local_iov = {
                                            .iov_base = child_path,
                                            .iov_len = sizeof(child_path) - 1
                                        };
                                        struct iovec remote_iov = {
                                            .iov_base = (void*)path_addr,
                                            .iov_len = sizeof(child_path) - 1
                                        };

                                        ssize_t nread = process_vm_readv(child, &local_iov, 1, &remote_iov, 1, 0);
                                        if (nread > 0) {
                                            child_path[nread] = '\0';
                                            char rewritten_path[2048] = {0};
                                            int should_rewrite = 0;

                                            if (strncmp(child_path, "/bin/", 5) == 0) {
                                                snprintf(rewritten_path, sizeof(rewritten_path), "%s/bin/%s", rootfs_dir, child_path + 5);
                                                should_rewrite = 1;
                                            } else if (strcmp(child_path, "/bin") == 0) {
                                                snprintf(rewritten_path, sizeof(rewritten_path), "%s/bin", rootfs_dir);
                                                should_rewrite = 1;
                                            } else if (strncmp(child_path, "/system/", 8) == 0) {
                                                snprintf(rewritten_path, sizeof(rewritten_path), "%s/system/%s", rootfs_dir, child_path + 8);
                                                should_rewrite = 1;
                                            } else if (strcmp(child_path, "/system") == 0) {
                                                snprintf(rewritten_path, sizeof(rewritten_path), "%s/system", rootfs_dir);
                                                should_rewrite = 1;
                                            }

                                            if (should_rewrite) {
                                                size_t rew_len = strlen(rewritten_path) + 1;
                                                uintptr_t scratch = write_to_child_scratch(child, sp, rewritten_path, rew_len);
                                                if (scratch != 0) {
                                                    SET_ARCH_ARG2(regs, scratch);
                                                    set_child_regs(child, &regs);
                                                    printf("SUPERVISOR: Intercepted %s path=%s -> Rewrote to %s\n",
                                                           (syscall_nr == __NR_openat ? "openat" : "newfstatat"),
                                                           child_path, rewritten_path);
                                                    fflush(stdout);
                                                }
                                            }
                                        }
                                    }
                                } else if (syscall_nr == __NR_execve) {
                                    uintptr_t path_addr = ARCH_ARG1(regs);
                                    if (path_addr != 0) {
                                        char child_path[1024] = {0};
                                        struct iovec local_iov = {
                                            .iov_base = child_path,
                                            .iov_len = sizeof(child_path) - 1
                                        };
                                        struct iovec remote_iov = {
                                            .iov_base = (void*)path_addr,
                                            .iov_len = sizeof(child_path) - 1
                                        };

                                        ssize_t nread = process_vm_readv(child, &local_iov, 1, &remote_iov, 1, 0);
                                        if (nread > 0) {
                                            child_path[nread] = '\0';
                                            char busybox_path[2048] = {0};
                                            snprintf(busybox_path, sizeof(busybox_path), "%s/libbusybox.so", native_lib_dir);
                                            size_t bb_len = strlen(busybox_path) + 1;

                                            uintptr_t scratch = write_to_child_scratch(child, sp, busybox_path, bb_len);
                                            if (scratch != 0) {
                                                SET_ARCH_ARG1(regs, scratch);
                                                ensure_argv0_is_sh(child, ARCH_ARG2(regs), scratch);
                                                set_child_regs(child, &regs);
                                                printf("SUPERVISOR: Intercepted execve path=%s -> Rewrote to %s (argv[0]=sh)\n",
                                                       child_path, busybox_path);
                                                fflush(stdout);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        in_syscall = 0;
                    }
                } else if (sig != SIGTRAP && sig != SIGSTOP) {
                    next_sig = sig;
                }
            }
        }
        return 0;
    }
}
