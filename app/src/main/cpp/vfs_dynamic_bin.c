#include <stdio.h>
#include <stdlib.h>

// Function declared in libvfs_shared.so
extern const char* vfs_get_dynamic_marker(void);

int main(int argc, char *argv[]) {
    const char *marker = vfs_get_dynamic_marker();
    printf("DYNAMIC_BIN_EXEC: %s\n", marker);
    printf("GATE_5_RESULT: CONTAINER_LIFECYCLE_SUCCESS\n");
    fflush(stdout);
    return 0;
}
