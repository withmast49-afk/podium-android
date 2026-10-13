// Storage capability probe.
#include "CPodiumSupport.h"

#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

int podium_probe_shared_mmap(const char *directory) {
    char path[4096];
    if (snprintf(path, sizeof(path), "%s/.podium-probe", directory) >= (int)sizeof(path)) return ENAMETOOLONG;

    int fd = open(path, O_RDWR | O_CREAT | O_TRUNC, 0600);
    if (fd < 0) return errno;
    int result = 0;
    const size_t size = 1 << 16;
    if (ftruncate(fd, (off_t)size) != 0) {
        result = errno;
    } else {
        unsigned char *map = (unsigned char *)mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
        if (map == MAP_FAILED) {
            result = errno ? errno : EINVAL;
        } else {
            map[0] = 0x5a;
            map[size - 1] = 0xa5;
            if (msync(map, size, MS_SYNC) != 0) result = errno;
            munmap(map, size);
            if (result == 0) {
                // The write must have reached the file.
                unsigned char check[1] = {0};
                if (pread(fd, check, 1, 0) != 1 || check[0] != 0x5a) result = EIO;
            }
        }
    }
    close(fd);
    unlink(path);
    return result;
}
