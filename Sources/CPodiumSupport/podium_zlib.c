// Raw-DEFLATE decoding on top of the system zlib (the NDK ships libz).
#include "CPodiumSupport.h"

#include <limits.h>
#include <stdlib.h>
#include <string.h>
#include <zlib.h>

void *podium_inflate_new(void) {
    z_stream *z = (z_stream *)calloc(1, sizeof(z_stream));
    if (!z) return NULL;
    if (inflateInit2(z, -15) != Z_OK) { free(z); return NULL; }
    return z;
}

void podium_inflate_free(void *state) {
    if (!state) return;
    inflateEnd((z_stream *)state);
    free(state);
}

int podium_inflate_run(void *state, const uint8_t **src, size_t *src_size, uint8_t **dst, size_t *dst_size) {
    z_stream *z = (z_stream *)state;
    if (!z) return -1;
    int result = 0;
    // zlib counts in 32-bit units; feed it in pieces so big buffers work.
    for (;;) {
        uInt in_chunk = *src_size > UINT_MAX ? UINT_MAX : (uInt)*src_size;
        uInt out_chunk = *dst_size > UINT_MAX ? UINT_MAX : (uInt)*dst_size;
        z->next_in = (Bytef *)*src;
        z->avail_in = in_chunk;
        z->next_out = (Bytef *)*dst;
        z->avail_out = out_chunk;
        int status = inflate(z, Z_NO_FLUSH);
        size_t consumed = in_chunk - z->avail_in;
        size_t produced = out_chunk - z->avail_out;
        *src += consumed;  *src_size -= consumed;
        *dst += produced;  *dst_size -= produced;
        if (status == Z_STREAM_END) { result = 1; break; }
        if (status == Z_OK) {
            if (consumed == 0 && produced == 0) break;
            if (*src_size == 0 || *dst_size == 0) break;
            continue;
        }
        if (status == Z_BUF_ERROR) break;   // no progress possible right now
        result = -1;                        // Z_DATA_ERROR, Z_MEM_ERROR, Z_NEED_DICT, ...
        break;
    }
    return result;
}

size_t podium_inflate_buffer(uint8_t *dst, size_t dst_size, const uint8_t *src, size_t src_size) {
    z_stream z;
    memset(&z, 0, sizeof(z));
    if (dst_size > UINT_MAX || src_size > UINT_MAX) return 0;
    if (inflateInit2(&z, -15) != Z_OK) return 0;
    z.next_in = (Bytef *)src;
    z.avail_in = (uInt)src_size;
    z.next_out = dst;
    z.avail_out = (uInt)dst_size;
    int status = inflate(&z, Z_FINISH);
    size_t produced = dst_size - z.avail_out;
    inflateEnd(&z);
    if (status == Z_STREAM_END) return produced;
    // Output buffer exactly full before the end marker was consumed: the
    // data decoded fine, which is what Apple's decoder reports too.
    if ((status == Z_BUF_ERROR || status == Z_OK) && z.avail_out == 0) return produced;
    return 0;
}
