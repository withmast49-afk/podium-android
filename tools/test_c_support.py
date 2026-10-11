# Checks Sources/CPodiumSupport (AES, SHA-1, HMAC, inflate) against Python's own implementations.
#   pip install cryptography && python3 tools/test_c_support.py      (needs gcc and zlib headers)
import ctypes, os, hashlib, hmac, subprocess, tempfile, zlib
_here = os.path.dirname(os.path.abspath(__file__))
_src = os.path.join(_here, "..", "Sources", "CPodiumSupport")
_lib = os.path.join(tempfile.mkdtemp(), "libpsup.so")
subprocess.check_call(["gcc", "-O2", "-Wall", "-fPIC", "-shared", "-I", os.path.join(_src, "include"),
                       os.path.join(_src, "podium_crypto.c"), os.path.join(_src, "podium_zlib.c"),
                       os.path.join(_src, "podium_jit.c"), "-lz", "-o", _lib])
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
lib = ctypes.CDLL(_lib)
c_sz = ctypes.c_size_t; vp = ctypes.c_void_p
lib.podium_aes_crypt.argtypes = [ctypes.c_int, ctypes.c_int, vp, c_sz, vp, vp, vp, c_sz]
lib.podium_sha1.argtypes = [vp, c_sz, vp]
lib.podium_hmac_sha1.argtypes = [vp, c_sz, vp, c_sz, vp]
lib.podium_inflate_buffer.restype = c_sz
lib.podium_inflate_buffer.argtypes = [vp, c_sz, vp, c_sz]
lib.podium_inflate_new.restype = vp
lib.podium_inflate_free.argtypes = [vp]
lib.podium_inflate_run.argtypes = [vp, ctypes.POINTER(vp), ctypes.POINTER(c_sz), ctypes.POINTER(vp), ctypes.POINTER(c_sz)]

def cb(b): return ctypes.create_string_buffer(b, len(b)) if len(b) else ctypes.create_string_buffer(1)
def aes(decrypt, cbc, key, iv, data):
    out = ctypes.create_string_buffer(len(data)); k = cb(key); i = cb(iv); d = cb(data)
    r = lib.podium_aes_crypt(int(decrypt), int(cbc), ctypes.addressof(k), len(key), ctypes.addressof(i), ctypes.addressof(d), ctypes.addressof(out), len(data))
    assert r == 0, r
    return out.raw

bad = 0
for ks in (16, 24, 32):
    for n in (16, 32, 4096, 512*5):
        key = os.urandom(ks); iv = os.urandom(16); data = os.urandom(n)
        for cbc in (0, 1):
            mode = modes.CBC(iv) if cbc else modes.ECB()
            enc_ref = Cipher(algorithms.AES(key), mode).encryptor().update(data)
            if aes(False, cbc, key, iv, data) != enc_ref: bad += 1; print("enc mismatch", ks, n, cbc)
            if aes(True, cbc, key, iv, enc_ref) != data: bad += 1; print("dec mismatch", ks, n, cbc)
print("aes mismatches:", bad)
key = bytes.fromhex("000102030405060708090a0b0c0d0e0f"); pt = bytes.fromhex("00112233445566778899aabbccddeeff")
print("fips197 aes128:", aes(False, 0, key, b"\0"*16, pt).hex() == "69c4e0d86a7b0430d8cdb78070b4c55a")
print("bad key len rejected:", lib.podium_aes_crypt(0,0,ctypes.addressof(cb(b"123")),3,0,ctypes.addressof(cb(pt)),ctypes.addressof(ctypes.create_string_buffer(16)),16) == -1)

bad = 0
for n in list(range(0, 200)) + [4096, 100000]:
    d = os.urandom(n); dd = cb(d); out = ctypes.create_string_buffer(20)
    lib.podium_sha1(ctypes.addressof(dd), n, ctypes.addressof(out))
    if out.raw != hashlib.sha1(d).digest(): bad += 1; print("sha1 mismatch", n)
    for kl in (0, 1, 20, 64, 65, 100):
        k = os.urandom(kl); kk = cb(k)
        lib.podium_hmac_sha1(ctypes.addressof(kk), kl, ctypes.addressof(dd), n, ctypes.addressof(out))
        if out.raw != hmac.new(k, d, hashlib.sha1).digest(): bad += 1; print("hmac mismatch", n, kl)
print("sha1/hmac mismatches:", bad)

bad = 0
for n in (1, 100, 10000, 3_000_000):
    d = (os.urandom(50) * (n // 50 + 1))[:n] if n > 1000 else os.urandom(n)
    co = zlib.compressobj(wbits=-15); raw = co.compress(d) + co.flush()
    out = ctypes.create_string_buffer(n); r = cb(raw)
    got = lib.podium_inflate_buffer(ctypes.addressof(out), n, ctypes.addressof(r), len(raw))
    if got != n or out.raw != d: bad += 1; print("buffer mismatch", n, got)
    if n > 1:
        small = ctypes.create_string_buffer(n-1)
        if lib.podium_inflate_buffer(ctypes.addressof(small), n-1, ctypes.addressof(r), len(raw)) == n: bad += 1; print("small buf wrongly ok", n)
    trunc = cb(raw[:len(raw)//2])
    if n > 100 and lib.podium_inflate_buffer(ctypes.addressof(out), n, ctypes.addressof(trunc), len(raw)//2) == n: bad += 1; print("truncated wrongly ok", n)
print("inflate buffer mismatches:", bad)

d = os.urandom(70000) + b"abc"*400000
co = zlib.compressobj(wbits=-15); raw = co.compress(d) + co.flush()
st = lib.podium_inflate_new(); inbuf = cb(raw)
src = vp(ctypes.addressof(inbuf)); ssz = c_sz(len(raw)); out = bytearray(); chunk = ctypes.create_string_buffer(65536); r = 0
for _ in range(10000):
    dst = vp(ctypes.addressof(chunk)); dsz = c_sz(65536)
    r = lib.podium_inflate_run(st, ctypes.byref(src), ctypes.byref(ssz), ctypes.byref(dst), ctypes.byref(dsz))
    out += chunk.raw[:65536 - dsz.value]
    if r != 0: break
print("stream end:", r == 1, "equal:", bytes(out) == d, "src left:", ssz.value)
lib.podium_inflate_free(st)
# corrupt stream -> -1
st = lib.podium_inflate_new(); junk = cb(b"\xff"*64); src = vp(ctypes.addressof(junk)); ssz = c_sz(64)
dst = vp(ctypes.addressof(chunk)); dsz = c_sz(65536)
print("corrupt -> -1:", lib.podium_inflate_run(st, ctypes.byref(src), ctypes.byref(ssz), ctypes.byref(dst), ctypes.byref(dsz)) == -1)
lib.podium_inflate_free(st)
