#include "node_codec.h"

#include <cstring>

static void put_u8(std::string &o, uint8_t v) { o.push_back((char) v); }

static void put_u32(std::string &o, uint32_t v) {
    for (int i = 0; i < 4; i++) o.push_back((char) ((v >> (8 * i)) & 0xff));
}

static void put_i64(std::string &o, int64_t v) {
    uint64_t u = (uint64_t) v;
    for (int i = 0; i < 8; i++) o.push_back((char) ((u >> (8 * i)) & 0xff));
}

static void put_f64(std::string &o, double v) {
    uint64_t bits;
    memcpy(&bits, &v, 8);
    put_i64(o, (int64_t) bits);
}

static void put_str(std::string &o, const char *s) {
    size_t n = s ? strlen(s) : 0;
    put_u32(o, (uint32_t) n);
    if (n) o.append(s, n);
}

void encode_none(std::string &out) { put_u8(out, MPV_FORMAT_NONE); }
void encode_i32(std::string &out, int32_t v) { put_u32(out, (uint32_t) v); }
void encode_i64(std::string &out, int64_t v) { put_i64(out, v); }

void encode_string(std::string &out, const char *s) { put_u8(out, MPV_FORMAT_STRING); put_str(out, s); }
void encode_flag(std::string &out, bool v) { put_u8(out, MPV_FORMAT_FLAG); put_u8(out, v ? 1 : 0); }
void encode_int64_node(std::string &out, int64_t v) { put_u8(out, MPV_FORMAT_INT64); put_i64(out, v); }
void encode_double_node(std::string &out, double v) { put_u8(out, MPV_FORMAT_DOUBLE); put_f64(out, v); }
void encode_map_header(std::string &out, uint32_t count) { put_u8(out, MPV_FORMAT_NODE_MAP); put_u32(out, count); }
void encode_key(std::string &out, const char *key) { put_str(out, key); }

void encode_node(std::string &out, const mpv_node *n) {
    if (!n) { encode_none(out); return; }
    switch (n->format) {
    case MPV_FORMAT_STRING:
    case MPV_FORMAT_OSD_STRING:
        encode_string(out, n->u.string);
        break;
    case MPV_FORMAT_FLAG:
        encode_flag(out, n->u.flag);
        break;
    case MPV_FORMAT_INT64:
        encode_int64_node(out, n->u.int64);
        break;
    case MPV_FORMAT_DOUBLE:
        encode_double_node(out, n->u.double_);
        break;
    case MPV_FORMAT_NODE_ARRAY: {
        put_u8(out, MPV_FORMAT_NODE_ARRAY);
        int c = n->u.list ? n->u.list->num : 0;
        put_u32(out, (uint32_t) c);
        for (int i = 0; i < c; i++) encode_node(out, &n->u.list->values[i]);
        break;
    }
    case MPV_FORMAT_NODE_MAP: {
        put_u8(out, MPV_FORMAT_NODE_MAP);
        int c = n->u.list ? n->u.list->num : 0;
        put_u32(out, (uint32_t) c);
        for (int i = 0; i < c; i++) {
            put_str(out, n->u.list->keys[i]);
            encode_node(out, &n->u.list->values[i]);
        }
        break;
    }
    case MPV_FORMAT_BYTE_ARRAY: {
        put_u8(out, MPV_FORMAT_BYTE_ARRAY);
        size_t sz = n->u.ba ? n->u.ba->size : 0;
        put_u32(out, (uint32_t) sz);
        if (sz) out.append((const char *) n->u.ba->data, sz);
        break;
    }
    default:
        encode_none(out);
        break;
    }
}

static bool get_u8(const uint8_t *d, size_t len, size_t &p, uint8_t &v) {
    if (p + 1 > len) return false;
    v = d[p++];
    return true;
}

static bool get_u32(const uint8_t *d, size_t len, size_t &p, uint32_t &v) {
    if (p + 4 > len) return false;
    v = (uint32_t) d[p] | ((uint32_t) d[p + 1] << 8) | ((uint32_t) d[p + 2] << 16) | ((uint32_t) d[p + 3] << 24);
    p += 4;
    return true;
}

static bool get_i64(const uint8_t *d, size_t len, size_t &p, int64_t &v) {
    if (p + 8 > len) return false;
    uint64_t u = 0;
    for (int i = 0; i < 8; i++) u |= ((uint64_t) d[p + i]) << (8 * i);
    p += 8;
    v = (int64_t) u;
    return true;
}

static bool get_bytes(const uint8_t *d, size_t len, size_t &p, size_t n, const uint8_t *&out) {
    if (n > len - p) return false;
    out = d + p;
    p += n;
    return true;
}

static char *keep_string(NodeArena &a, const uint8_t *b, size_t n) {
    a.strings.emplace_back(new std::string((const char *) b, n));
    return (char *) a.strings.back()->c_str();
}

bool decode_node(const uint8_t *d, size_t len, size_t &p, mpv_node *out, NodeArena &a, int depth) {
    uint8_t tag;
    if (depth > 64 || !get_u8(d, len, p, tag)) return false;
    switch (tag) {
    case MPV_FORMAT_NONE:
        out->format = MPV_FORMAT_NONE;
        return true;
    case MPV_FORMAT_STRING: {
        uint32_t n; const uint8_t *b;
        if (!get_u32(d, len, p, n) || !get_bytes(d, len, p, n, b)) return false;
        out->format = MPV_FORMAT_STRING;
        out->u.string = keep_string(a, b, n);
        return true;
    }
    case MPV_FORMAT_FLAG: {
        uint8_t v;
        if (!get_u8(d, len, p, v)) return false;
        out->format = MPV_FORMAT_FLAG;
        out->u.flag = v ? 1 : 0;
        return true;
    }
    case MPV_FORMAT_INT64: {
        int64_t v;
        if (!get_i64(d, len, p, v)) return false;
        out->format = MPV_FORMAT_INT64;
        out->u.int64 = v;
        return true;
    }
    case MPV_FORMAT_DOUBLE: {
        int64_t bits;
        if (!get_i64(d, len, p, bits)) return false;
        double v;
        memcpy(&v, &bits, 8);
        out->format = MPV_FORMAT_DOUBLE;
        out->u.double_ = v;
        return true;
    }
    case MPV_FORMAT_NODE_ARRAY:
    case MPV_FORMAT_NODE_MAP: {
        uint32_t n;
        if (!get_u32(d, len, p, n)) return false;
        // Each entry takes at least one byte, a map entry five; this also keeps n within an int.
        if (n > (len - p) / (tag == MPV_FORMAT_NODE_MAP ? 5 : 1)) return false;
        a.lists.emplace_back(new mpv_node_list{});
        mpv_node_list *l = a.lists.back().get();
        l->num = (int) n;
        a.node_lists.emplace_back(new mpv_node[n ? n : 1]());
        l->values = a.node_lists.back().get();
        l->keys = nullptr;
        if (tag == MPV_FORMAT_NODE_MAP) {
            a.key_lists.emplace_back(new char *[n ? n : 1]());
            l->keys = a.key_lists.back().get();
        }
        for (uint32_t i = 0; i < n; i++) {
            if (tag == MPV_FORMAT_NODE_MAP) {
                uint32_t kn; const uint8_t *kb;
                if (!get_u32(d, len, p, kn) || !get_bytes(d, len, p, kn, kb)) return false;
                l->keys[i] = keep_string(a, kb, kn);
            }
            if (!decode_node(d, len, p, &l->values[i], a, depth + 1)) return false;
        }
        out->format = (mpv_format) tag;
        out->u.list = l;
        return true;
    }
    case MPV_FORMAT_BYTE_ARRAY: {
        uint32_t n; const uint8_t *b;
        if (!get_u32(d, len, p, n) || !get_bytes(d, len, p, n, b)) return false;
        a.strings.emplace_back(new std::string((const char *) b, n));
        a.byte_arrays.emplace_back(new mpv_byte_array{});
        a.byte_arrays.back()->data = (void *) a.strings.back()->data();
        a.byte_arrays.back()->size = n;
        out->format = MPV_FORMAT_BYTE_ARRAY;
        out->u.ba = a.byte_arrays.back().get();
        return true;
    }
    default:
        return false;
    }
}
