#ifndef CPODIUMSUPPORT_H
#define CPODIUMSUPPORT_H

#include <stddef.h>
#include <stdint.h>
#include <stdbool.h>

/* ---- JIT memory (Android / Linux; the iOS build keeps Native/PodiumJIT.c) ---- */

/// How `podium_jit_allocate` got its memory, for the log.
typedef enum {
    PodiumJITModeNone = 0,
    /// One read-write-execute anonymous mapping.
    PodiumJITModeRWX = 1,
    /// Two mappings of the same memfd: one executable, one writable.
    PodiumJITModeDualMapped = 2,
} PodiumJITMode;

bool podium_jit_debugger_attached(void);

/// Allocates `size` bytes for translated code. Returns where the code runs
/// from and stores where to write it in `writable`; NULL if this host can't
/// run generated AArch64 code (not arm64, or the kernel refuses).
void *podium_jit_allocate(size_t size, void **writable, PodiumJITMode *mode);

/// Makes the instruction cache coherent for [start, start + length).
void podium_clear_cache(void *start, size_t length);

/* ---- AES (128/192/256), ECB or CBC, no padding ---- */

/// `length` must be a multiple of 16. `iv` (16 bytes) is only read for CBC.
/// Returns 0 on success, -1 on bad key length / length.
int podium_aes_crypt(int decrypt, int cbc, const uint8_t *key, size_t key_len,
                     const uint8_t *iv, const uint8_t *in, uint8_t *out, size_t length);

/* ---- SHA-1 / HMAC-SHA-1 ---- */

void podium_sha1(const uint8_t *data, size_t length, uint8_t out[20]);
void podium_hmac_sha1(const uint8_t *key, size_t key_length,
                      const uint8_t *data, size_t length, uint8_t out[20]);

/* ---- Raw DEFLATE (what Apple's COMPRESSION_ZLIB decodes) ---- */

void *podium_inflate_new(void);
void podium_inflate_free(void *state);
/// Advances `*src`/`*dst` and shrinks `*src_size`/`*dst_size` by what was
/// consumed/produced. Returns 0 (more to do), 1 (end of stream), -1 (error).
int podium_inflate_run(void *state, const uint8_t **src, size_t *src_size,
                       uint8_t **dst, size_t *dst_size);
/// One-shot decode of a raw DEFLATE buffer; returns bytes produced, 0 on error.
size_t podium_inflate_buffer(uint8_t *dst, size_t dst_size, const uint8_t *src, size_t src_size);

#endif
