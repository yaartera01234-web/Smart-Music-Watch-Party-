#include "utf8.h"

#include <cstdint>
#include <vector>

std::string utf8_from_jstring(JNIEnv *env, jstring s) {
    std::string out;
    if (!s) return out;
    jsize n = env->GetStringLength(s);
    std::vector<jchar> u(n);
    env->GetStringRegion(s, 0, n, u.data());
    out.reserve(n);
    for (jsize i = 0; i < n; i++) {
        uint32_t c = u[i];
        if (c >= 0xD800 && c <= 0xDBFF && i + 1 < n && u[i + 1] >= 0xDC00 && u[i + 1] <= 0xDFFF) {
            c = 0x10000 + ((c - 0xD800) << 10) + (u[i + 1] - 0xDC00);
            i++;
        } else if (c >= 0xD800 && c <= 0xDFFF) {
            c = 0xFFFD;
        }
        if (c < 0x80) {
            out.push_back(static_cast<char>(c));
        } else if (c < 0x800) {
            out.push_back(static_cast<char>(0xC0 | (c >> 6)));
            out.push_back(static_cast<char>(0x80 | (c & 0x3F)));
        } else if (c < 0x10000) {
            out.push_back(static_cast<char>(0xE0 | (c >> 12)));
            out.push_back(static_cast<char>(0x80 | ((c >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (c & 0x3F)));
        } else {
            out.push_back(static_cast<char>(0xF0 | (c >> 18)));
            out.push_back(static_cast<char>(0x80 | ((c >> 12) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | ((c >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (c & 0x3F)));
        }
    }
    return out;
}

jstring jstring_from_utf8(JNIEnv *env, const char *s) {
    if (!s) return nullptr;
    std::vector<jchar> u;
    const auto *p = reinterpret_cast<const unsigned char *>(s);
    while (*p) {
        uint32_t c = *p;
        int extra;
        uint32_t min;
        if (c < 0x80) {
            u.push_back(static_cast<jchar>(c));
            p++;
            continue;
        } else if ((c & 0xE0) == 0xC0) {
            extra = 1; c &= 0x1F; min = 0x80;
        } else if ((c & 0xF0) == 0xE0) {
            extra = 2; c &= 0x0F; min = 0x800;
        } else if ((c & 0xF8) == 0xF0) {
            extra = 3; c &= 0x07; min = 0x10000;
        } else {
            u.push_back(0xFFFD);
            p++;
            continue;
        }
        int k = 1;
        // A continuation byte is 10xxxxxx; the terminating NUL fails this test too.
        for (; k <= extra && (p[k] & 0xC0) == 0x80; k++) c = (c << 6) | (p[k] & 0x3F);
        if (k <= extra || c < min || c > 0x10FFFF || (c >= 0xD800 && c <= 0xDFFF)) {
            u.push_back(0xFFFD);
            p += k;
            continue;
        }
        p += extra + 1;
        if (c >= 0x10000) {
            c -= 0x10000;
            u.push_back(static_cast<jchar>(0xD800 + (c >> 10)));
            u.push_back(static_cast<jchar>(0xDC00 + (c & 0x3FF)));
        } else {
            u.push_back(static_cast<jchar>(c));
        }
    }
    static const jchar empty = 0;
    return env->NewString(u.empty() ? &empty : u.data(), static_cast<jsize>(u.size()));
}
