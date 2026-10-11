// Executable memory for the DBT on Android (and Linux). iOS keeps using
// Podium/Emulator/CPU/DBT/Native/PodiumJIT.c (StikDebug / MAP_JIT).
#include "CPodiumSupport.h"

#if !defined(__APPLE__)

#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <errno.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>

#if defined(__ANDROID__)
#include <android/log.h>
#endif

static void jit_log(const char *format, ...) {
    char line[256];
    va_list args;
    va_start(args, format);
    vsnprintf(line, sizeof(line), format, args);
    va_end(args);
#if defined(__ANDROID__)
    __android_log_print(ANDROID_LOG_INFO, "podium-jit", "%s", line);
#else
    fprintf(stderr, "[podium-jit] %s\n", line);
#endif
}

bool podium_jit_debugger_attached(void) {
    return false;
}

void podium_clear_cache(void *start, size_t length) {
    __builtin___clear_cache((char *)start, (char *)start + length);
}

void *podium_jit_allocate(size_t size, void **writable, PodiumJITMode *mode) {
    *writable = NULL;
    *mode = PodiumJITModeNone;
#if defined(__aarch64__)
    // Android lets an app map anonymous RWX memory (every JIT emulator relies on it).
    void *rwx = mmap(NULL, size, PROT_READ | PROT_WRITE | PROT_EXEC, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (rwx != MAP_FAILED) {
        *writable = rwx;
        *mode = PodiumJITModeRWX;
        jit_log("RWX region at %p, %zu bytes", rwx, size);
        return rwx;
    }
    jit_log("RWX mapping refused (errno %d); trying a dual mapping", errno);

#ifdef __NR_memfd_create
    int fd = (int)syscall(__NR_memfd_create, "podium-jit", 0);
    if (fd >= 0) {
        if (ftruncate(fd, (off_t)size) == 0) {
            void *executable = mmap(NULL, size, PROT_READ | PROT_EXEC, MAP_SHARED, fd, 0);
            void *alias = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
            if (executable != MAP_FAILED && alias != MAP_FAILED) {
                close(fd);
                *writable = alias;
                *mode = PodiumJITModeDualMapped;
                jit_log("dual-mapped region: runs at %p, written at %p, %zu bytes", executable, alias, size);
                return executable;
            }
            if (executable != MAP_FAILED) munmap(executable, size);
            if (alias != MAP_FAILED) munmap(alias, size);
        }
        close(fd);
    }
#endif
    jit_log("no executable memory available; falling back to the interpreter");
#else
    jit_log("host is not AArch64; the DBT can't run here");
#endif
    return NULL;
}

#endif /* !__APPLE__ */
