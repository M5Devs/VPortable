cmake_minimum_required(VERSION 3.22.1)

project("vportable_native")

add_executable(libprobe_ptrace.so probe_ptrace.c)
set_target_properties(libprobe_ptrace.so PROPERTIES OUTPUT_NAME "libprobe_ptrace.so")
# The supervisor reports diagnostics through logcat instead of the PTY it shares
# with the guest shell, so it needs liblog.
target_link_libraries(libprobe_ptrace.so log)

add_library(vfs_shared SHARED vfs_shared.c)

add_executable(vfs_dynamic_bin vfs_dynamic_bin.c)
target_link_libraries(vfs_dynamic_bin vfs_shared)
set_target_properties(vfs_dynamic_bin PROPERTIES
    OUTPUT_NAME "libvfs_dynamic_bin.so"
    LINK_FLAGS "-Wl,-rpath,\\$ORIGIN/../lib -Wl,-rpath,\\$ORIGIN/../lib64 -Wl,-rpath,/lib -Wl,-rpath,/lib64"
)

add_library(vportable_pty SHARED terminal_pty.c)
target_link_libraries(vportable_pty log)
