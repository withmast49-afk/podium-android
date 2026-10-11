// Small, dependency-free AES / SHA-1 / HMAC-SHA-1 for hosts without CommonCrypto.
#include "CPodiumSupport.h"

#include <pthread.h>
#include <string.h>

/* ============================ AES ============================ */

static uint8_t g_sbox[256], g_inv_sbox[256];
static uint32_t g_te0[256], g_te1[256], g_te2[256], g_te3[256];
static uint32_t g_td0[256], g_td1[256], g_td2[256], g_td3[256];
static pthread_once_t g_aes_once = PTHREAD_ONCE_INIT;

static uint8_t xtime(uint8_t x) { return (uint8_t)((x << 1) ^ ((x & 0x80) ? 0x1b : 0)); }

static uint8_t gmul(uint8_t a, uint8_t b) {
    uint8_t p = 0;
    for (int i = 0; i < 8; i++) {
        if (b & 1) p ^= a;
        a = xtime(a);
        b >>= 1;
    }
    return p;
}

static uint32_t ror32(uint32_t x, int n) { return (x >> n) | (x << (32 - n)); }

static void aes_init_tables(void) {
    // S-box from the multiplicative inverse + affine transform.
    uint8_t p = 1, q = 1;
    do {
        p = (uint8_t)(p ^ (uint8_t)(p << 1) ^ ((p & 0x80) ? 0x1b : 0));      // p *= 3
        q ^= (uint8_t)(q << 1);                                               // q /= 3
        q ^= (uint8_t)(q << 2);
        q ^= (uint8_t)(q << 4);
        if (q & 0x80) q ^= 0x09;
        uint8_t x = (uint8_t)(q ^ ((q << 1) | (q >> 7)) ^ ((q << 2) | (q >> 6)) ^ ((q << 3) | (q >> 5)) ^ ((q << 4) | (q >> 4)));
        g_sbox[p] = (uint8_t)(x ^ 0x63);
    } while (p != 1);
    g_sbox[0] = 0x63;
    for (int i = 0; i < 256; i++) g_inv_sbox[g_sbox[i]] = (uint8_t)i;

    for (int i = 0; i < 256; i++) {
        uint8_t s = g_sbox[i];
        uint32_t t = ((uint32_t)gmul(s, 2) << 24) | ((uint32_t)s << 16) | ((uint32_t)s << 8) | gmul(s, 3);
        g_te0[i] = t; g_te1[i] = ror32(t, 8); g_te2[i] = ror32(t, 16); g_te3[i] = ror32(t, 24);
        uint8_t si = g_inv_sbox[i];
        uint32_t d = ((uint32_t)gmul(si, 14) << 24) | ((uint32_t)gmul(si, 9) << 16) | ((uint32_t)gmul(si, 13) << 8) | gmul(si, 11);
        g_td0[i] = d; g_td1[i] = ror32(d, 8); g_td2[i] = ror32(d, 16); g_td3[i] = ror32(d, 24);
    }
}

typedef struct {
    int rounds;
    uint32_t enc[60];
    uint32_t dec[60];
} aes_key;

static uint32_t load_be32(const uint8_t *p) {
    return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) | ((uint32_t)p[2] << 8) | p[3];
}
static void store_be32(uint8_t *p, uint32_t v) {
    p[0] = (uint8_t)(v >> 24); p[1] = (uint8_t)(v >> 16); p[2] = (uint8_t)(v >> 8); p[3] = (uint8_t)v;
}

static uint32_t sub_word(uint32_t w) {
    return ((uint32_t)g_sbox[w >> 24] << 24) | ((uint32_t)g_sbox[(w >> 16) & 0xff] << 16) |
           ((uint32_t)g_sbox[(w >> 8) & 0xff] << 8) | g_sbox[w & 0xff];
}

static int aes_set_key(aes_key *k, const uint8_t *key, size_t key_len) {
    if (key_len != 16 && key_len != 24 && key_len != 32) return -1;
    int nk = (int)(key_len / 4);
    k->rounds = nk + 6;
    int total = 4 * (k->rounds + 1);
    for (int i = 0; i < nk; i++) k->enc[i] = load_be32(key + 4 * i);
    uint32_t rcon = 1;
    for (int i = nk; i < total; i++) {
        uint32_t t = k->enc[i - 1];
        if (i % nk == 0) {
            t = sub_word((t << 8) | (t >> 24)) ^ (rcon << 24);
            rcon = xtime((uint8_t)rcon);
        } else if (nk > 6 && i % nk == 4) {
            t = sub_word(t);
        }
        k->enc[i] = k->enc[i - nk] ^ t;
    }
    // Decryption schedule for the equivalent inverse cipher.
    for (int r = 0; r <= k->rounds; r++) {
        for (int c = 0; c < 4; c++) {
            uint32_t w = k->enc[4 * (k->rounds - r) + c];
            if (r != 0 && r != k->rounds) {
                w = g_td0[g_sbox[w >> 24]] ^ g_td1[g_sbox[(w >> 16) & 0xff]] ^
                    g_td2[g_sbox[(w >> 8) & 0xff]] ^ g_td3[g_sbox[w & 0xff]];
            }
            k->dec[4 * r + c] = w;
        }
    }
    return 0;
}

static void aes_encrypt_block(const aes_key *k, const uint8_t in[16], uint8_t out[16]) {
    const uint32_t *rk = k->enc;
    uint32_t s0 = load_be32(in) ^ rk[0], s1 = load_be32(in + 4) ^ rk[1];
    uint32_t s2 = load_be32(in + 8) ^ rk[2], s3 = load_be32(in + 12) ^ rk[3];
    for (int r = 1; r < k->rounds; r++) {
        rk += 4;
        uint32_t t0 = g_te0[s0 >> 24] ^ g_te1[(s1 >> 16) & 0xff] ^ g_te2[(s2 >> 8) & 0xff] ^ g_te3[s3 & 0xff] ^ rk[0];
        uint32_t t1 = g_te0[s1 >> 24] ^ g_te1[(s2 >> 16) & 0xff] ^ g_te2[(s3 >> 8) & 0xff] ^ g_te3[s0 & 0xff] ^ rk[1];
        uint32_t t2 = g_te0[s2 >> 24] ^ g_te1[(s3 >> 16) & 0xff] ^ g_te2[(s0 >> 8) & 0xff] ^ g_te3[s1 & 0xff] ^ rk[2];
        uint32_t t3 = g_te0[s3 >> 24] ^ g_te1[(s0 >> 16) & 0xff] ^ g_te2[(s1 >> 8) & 0xff] ^ g_te3[s2 & 0xff] ^ rk[3];
        s0 = t0; s1 = t1; s2 = t2; s3 = t3;
    }
    rk += 4;
    uint32_t o0 = (((uint32_t)g_sbox[s0 >> 24] << 24) | ((uint32_t)g_sbox[(s1 >> 16) & 0xff] << 16) | ((uint32_t)g_sbox[(s2 >> 8) & 0xff] << 8) | g_sbox[s3 & 0xff]) ^ rk[0];
    uint32_t o1 = (((uint32_t)g_sbox[s1 >> 24] << 24) | ((uint32_t)g_sbox[(s2 >> 16) & 0xff] << 16) | ((uint32_t)g_sbox[(s3 >> 8) & 0xff] << 8) | g_sbox[s0 & 0xff]) ^ rk[1];
    uint32_t o2 = (((uint32_t)g_sbox[s2 >> 24] << 24) | ((uint32_t)g_sbox[(s3 >> 16) & 0xff] << 16) | ((uint32_t)g_sbox[(s0 >> 8) & 0xff] << 8) | g_sbox[s1 & 0xff]) ^ rk[2];
    uint32_t o3 = (((uint32_t)g_sbox[s3 >> 24] << 24) | ((uint32_t)g_sbox[(s0 >> 16) & 0xff] << 16) | ((uint32_t)g_sbox[(s1 >> 8) & 0xff] << 8) | g_sbox[s2 & 0xff]) ^ rk[3];
    store_be32(out, o0); store_be32(out + 4, o1); store_be32(out + 8, o2); store_be32(out + 12, o3);
}

static void aes_decrypt_block(const aes_key *k, const uint8_t in[16], uint8_t out[16]) {
    const uint32_t *rk = k->dec;
    uint32_t s0 = load_be32(in) ^ rk[0], s1 = load_be32(in + 4) ^ rk[1];
    uint32_t s2 = load_be32(in + 8) ^ rk[2], s3 = load_be32(in + 12) ^ rk[3];
    for (int r = 1; r < k->rounds; r++) {
        rk += 4;
        uint32_t t0 = g_td0[s0 >> 24] ^ g_td1[(s3 >> 16) & 0xff] ^ g_td2[(s2 >> 8) & 0xff] ^ g_td3[s1 & 0xff] ^ rk[0];
        uint32_t t1 = g_td0[s1 >> 24] ^ g_td1[(s0 >> 16) & 0xff] ^ g_td2[(s3 >> 8) & 0xff] ^ g_td3[s2 & 0xff] ^ rk[1];
        uint32_t t2 = g_td0[s2 >> 24] ^ g_td1[(s1 >> 16) & 0xff] ^ g_td2[(s0 >> 8) & 0xff] ^ g_td3[s3 & 0xff] ^ rk[2];
        uint32_t t3 = g_td0[s3 >> 24] ^ g_td1[(s2 >> 16) & 0xff] ^ g_td2[(s1 >> 8) & 0xff] ^ g_td3[s0 & 0xff] ^ rk[3];
        s0 = t0; s1 = t1; s2 = t2; s3 = t3;
    }
    rk += 4;
    uint32_t o0 = (((uint32_t)g_inv_sbox[s0 >> 24] << 24) | ((uint32_t)g_inv_sbox[(s3 >> 16) & 0xff] << 16) | ((uint32_t)g_inv_sbox[(s2 >> 8) & 0xff] << 8) | g_inv_sbox[s1 & 0xff]) ^ rk[0];
    uint32_t o1 = (((uint32_t)g_inv_sbox[s1 >> 24] << 24) | ((uint32_t)g_inv_sbox[(s0 >> 16) & 0xff] << 16) | ((uint32_t)g_inv_sbox[(s3 >> 8) & 0xff] << 8) | g_inv_sbox[s2 & 0xff]) ^ rk[1];
    uint32_t o2 = (((uint32_t)g_inv_sbox[s2 >> 24] << 24) | ((uint32_t)g_inv_sbox[(s1 >> 16) & 0xff] << 16) | ((uint32_t)g_inv_sbox[(s0 >> 8) & 0xff] << 8) | g_inv_sbox[s3 & 0xff]) ^ rk[2];
    uint32_t o3 = (((uint32_t)g_inv_sbox[s3 >> 24] << 24) | ((uint32_t)g_inv_sbox[(s2 >> 16) & 0xff] << 16) | ((uint32_t)g_inv_sbox[(s1 >> 8) & 0xff] << 8) | g_inv_sbox[s0 & 0xff]) ^ rk[3];
    store_be32(out, o0); store_be32(out + 4, o1); store_be32(out + 8, o2); store_be32(out + 12, o3);
}

int podium_aes_crypt(int decrypt, int cbc, const uint8_t *key, size_t key_len,
                     const uint8_t *iv, const uint8_t *in, uint8_t *out, size_t length) {
    if (length % 16 != 0) return -1;
    pthread_once(&g_aes_once, aes_init_tables);
    aes_key k;
    if (aes_set_key(&k, key, key_len) != 0) return -1;
    uint8_t chain[16];
    if (cbc) {
        if (iv) memcpy(chain, iv, 16); else memset(chain, 0, 16);
    }
    for (size_t offset = 0; offset < length; offset += 16) {
        uint8_t block[16];
        if (!decrypt) {
            if (cbc) {
                for (int i = 0; i < 16; i++) block[i] = in[offset + i] ^ chain[i];
            } else {
                memcpy(block, in + offset, 16);
            }
            aes_encrypt_block(&k, block, out + offset);
            if (cbc) memcpy(chain, out + offset, 16);
        } else {
            uint8_t cipher[16];
            memcpy(cipher, in + offset, 16);   // `in` and `out` may alias
            aes_decrypt_block(&k, cipher, block);
            if (cbc) {
                for (int i = 0; i < 16; i++) out[offset + i] = block[i] ^ chain[i];
                memcpy(chain, cipher, 16);
            } else {
                memcpy(out + offset, block, 16);
            }
        }
    }
    return 0;
}

/* ============================ SHA-1 ============================ */

typedef struct {
    uint32_t h[5];
    uint64_t length;      // bytes
    uint8_t buffer[64];
    size_t used;
} sha1_ctx;

static uint32_t rol32(uint32_t x, int n) { return (x << n) | (x >> (32 - n)); }

static void sha1_block(sha1_ctx *c, const uint8_t *p) {
    uint32_t w[80];
    for (int i = 0; i < 16; i++) w[i] = load_be32(p + 4 * i);
    for (int i = 16; i < 80; i++) w[i] = rol32(w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16], 1);
    uint32_t a = c->h[0], b = c->h[1], cc = c->h[2], d = c->h[3], e = c->h[4];
    for (int i = 0; i < 80; i++) {
        uint32_t f, k;
        if (i < 20)      { f = (b & cc) | (~b & d);            k = 0x5A827999; }
        else if (i < 40) { f = b ^ cc ^ d;                      k = 0x6ED9EBA1; }
        else if (i < 60) { f = (b & cc) | (b & d) | (cc & d);   k = 0x8F1BBCDC; }
        else             { f = b ^ cc ^ d;                      k = 0xCA62C1D6; }
        uint32_t t = rol32(a, 5) + f + e + k + w[i];
        e = d; d = cc; cc = rol32(b, 30); b = a; a = t;
    }
    c->h[0] += a; c->h[1] += b; c->h[2] += cc; c->h[3] += d; c->h[4] += e;
}

static void sha1_init(sha1_ctx *c) {
    c->h[0] = 0x67452301; c->h[1] = 0xEFCDAB89; c->h[2] = 0x98BADCFE; c->h[3] = 0x10325476; c->h[4] = 0xC3D2E1F0;
    c->length = 0; c->used = 0;
}

static void sha1_update(sha1_ctx *c, const uint8_t *data, size_t length) {
    c->length += length;
    while (length > 0) {
        if (c->used == 0 && length >= 64) {
            sha1_block(c, data);
            data += 64; length -= 64;
            continue;
        }
        size_t take = 64 - c->used;
        if (take > length) take = length;
        memcpy(c->buffer + c->used, data, take);
        c->used += take; data += take; length -= take;
        if (c->used == 64) { sha1_block(c, c->buffer); c->used = 0; }
    }
}

static void sha1_final(sha1_ctx *c, uint8_t out[20]) {
    uint64_t bits = c->length * 8;
    uint8_t pad = 0x80;
    sha1_update(c, &pad, 1);
    uint8_t zero = 0;
    while (c->used != 56) sha1_update(c, &zero, 1);
    uint8_t len[8];
    for (int i = 0; i < 8; i++) len[i] = (uint8_t)(bits >> (56 - 8 * i));
    sha1_update(c, len, 8);
    for (int i = 0; i < 5; i++) store_be32(out + 4 * i, c->h[i]);
}

void podium_sha1(const uint8_t *data, size_t length, uint8_t out[20]) {
    sha1_ctx c;
    sha1_init(&c);
    if (length > 0) sha1_update(&c, data, length);
    sha1_final(&c, out);
}

void podium_hmac_sha1(const uint8_t *key, size_t key_length,
                      const uint8_t *data, size_t length, uint8_t out[20]) {
    uint8_t k[64];
    memset(k, 0, sizeof(k));
    if (key_length > 64) podium_sha1(key, key_length, k);
    else if (key_length > 0) memcpy(k, key, key_length);

    uint8_t ipad[64], opad[64];
    for (int i = 0; i < 64; i++) { ipad[i] = k[i] ^ 0x36; opad[i] = k[i] ^ 0x5c; }

    uint8_t inner[20];
    sha1_ctx c;
    sha1_init(&c);
    sha1_update(&c, ipad, 64);
    if (length > 0) sha1_update(&c, data, length);
    sha1_final(&c, inner);

    sha1_init(&c);
    sha1_update(&c, opad, 64);
    sha1_update(&c, inner, 20);
    sha1_final(&c, out);
}
