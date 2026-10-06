#pragma once

#include <mpv/client.h>

#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

/*
 * The wire format shared with Kotlin's NodeCodec.kt. Tags are mpv_format values, integers are
 * little-endian.
 *   NONE(0)                          nothing
 *   STRING(1)      u32 len, utf8 bytes
 *   FLAG(3)        u8
 *   INT64(4)       i64
 *   DOUBLE(5)      f64 bits
 *   NODE_ARRAY(7)  u32 count, then count nodes
 *   NODE_MAP(8)    u32 count, then count times (u32 keylen, key utf8, node)
 *   BYTE_ARRAY(9)  u32 len, bytes
 */
void encode_none(std::string &out);
void encode_i32(std::string &out, int32_t v);
void encode_i64(std::string &out, int64_t v);
void encode_node(std::string &out, const mpv_node *node);

/* Single nodes, and the pieces of a map written by hand: the header, then a key and a node per entry. */
void encode_string(std::string &out, const char *s);
void encode_flag(std::string &out, bool v);
void encode_int64_node(std::string &out, int64_t v);
void encode_double_node(std::string &out, double v);
void encode_map_header(std::string &out, uint32_t count);
void encode_key(std::string &out, const char *key);

/* Owns every allocation a decoded mpv_node tree points into. Keep it alive while the node is used. */
struct NodeArena {
    std::vector<std::unique_ptr<mpv_node[]>> node_lists;
    std::vector<std::unique_ptr<char *[]>> key_lists;
    std::vector<std::unique_ptr<mpv_node_list>> lists;
    std::vector<std::unique_ptr<mpv_byte_array>> byte_arrays;
    std::vector<std::unique_ptr<std::string>> strings;
};

/* Decodes one node starting at data[pos]. Returns false on a malformed buffer, including nesting deeper than 64. */
bool decode_node(const uint8_t *data, size_t len, size_t &pos, mpv_node *out, NodeArena &arena, int depth = 0);
