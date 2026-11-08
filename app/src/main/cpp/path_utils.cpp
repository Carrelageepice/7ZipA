#include "path_utils.h"

#include <cerrno>
#include <cstdint>

#include <sys/stat.h>
#include <sys/types.h>

namespace sz {

bool SanitizeRelativePath(const std::string &in, std::string &out) {
    out.clear();
    if (in.empty() || in.find('\0') != std::string::npos) return false;
    if (in[0] == '/' || in[0] == '\\') return false;
    if (in.size() >= 2 && in[1] == ':') return false;  // "C:..."

    size_t pos = 0;
    while (pos <= in.size()) {
        size_t next = in.find_first_of("/\\", pos);
        if (next == std::string::npos) next = in.size();
        const std::string part = in.substr(pos, next - pos);
        if (part == "..") return false;
        if (!part.empty() && part != ".") {
            if (!out.empty()) out += '/';
            out += part;
        }
        pos = next + 1;
    }
    return !out.empty();
}

bool MakeDirs(const std::string &path) {
    if (path.empty()) return false;
    std::string cur;
    size_t pos = 0;
    if (path[0] == '/') { cur = "/"; pos = 1; }
    while (pos <= path.size()) {
        size_t next = path.find('/', pos);
        if (next == std::string::npos) next = path.size();
        if (next > pos) {
            cur += path.substr(pos, next - pos);
            if (mkdir(cur.c_str(), 0775) != 0 && errno != EEXIST) return false;
            cur += '/';
        }
        pos = next + 1;
    }
    struct stat st;
    return stat(path.c_str(), &st) == 0 && S_ISDIR(st.st_mode);
}

bool ParentDirOf(const std::string &path, std::string &parent) {
    const size_t p = path.rfind('/');
    if (p == std::string::npos) return false;
    parent = path.substr(0, p);
    return true;
}

std::u16string Utf8ToUtf16(const std::string &s) {
    std::u16string out;
    out.reserve(s.size());
    const size_t n = s.size();
    size_t i = 0;
    while (i < n) {
        const unsigned char b = static_cast<unsigned char>(s[i]);
        uint32_t cp = 0xFFFD;
        size_t len = 1;
        if (b < 0x80) {
            cp = b;
        } else if (b >= 0xC2 && b < 0xE0 && i + 1 < n) {
            cp = ((b & 0x1Fu) << 6) | (static_cast<unsigned char>(s[i + 1]) & 0x3Fu);
            len = 2;
        } else if (b >= 0xE0 && b < 0xF0 && i + 2 < n) {
            cp = ((b & 0x0Fu) << 12) |
                 ((static_cast<unsigned char>(s[i + 1]) & 0x3Fu) << 6) |
                 (static_cast<unsigned char>(s[i + 2]) & 0x3Fu);
            len = 3;
        } else if (b >= 0xF0 && b < 0xF5 && i + 3 < n) {
            cp = ((b & 0x07u) << 18) |
                 ((static_cast<unsigned char>(s[i + 1]) & 0x3Fu) << 12) |
                 ((static_cast<unsigned char>(s[i + 2]) & 0x3Fu) << 6) |
                 (static_cast<unsigned char>(s[i + 3]) & 0x3Fu);
            len = 4;
        }
        i += len;
        if (cp >= 0x10000) {
            cp -= 0x10000;
            out += static_cast<char16_t>(0xD800 + (cp >> 10));
            out += static_cast<char16_t>(0xDC00 + (cp & 0x3FF));
        } else {
            out += static_cast<char16_t>(cp);
        }
    }
    return out;
}

std::string Utf16ToUtf8(const char16_t *s, size_t len) {
    std::string out;
    out.reserve(len);
    for (size_t i = 0; i < len; ++i) {
        uint32_t cp = s[i];
        if (cp >= 0xD800 && cp < 0xDC00 && i + 1 < len &&
            s[i + 1] >= 0xDC00 && s[i + 1] < 0xE000) {
            cp = 0x10000 + ((cp - 0xD800) << 10) + (s[i + 1] - 0xDC00);
            ++i;
        } else if (cp >= 0xD800 && cp < 0xE000) {
            cp = 0xFFFD;
        }
        if (cp < 0x80) {
            out += static_cast<char>(cp);
        } else if (cp < 0x800) {
            out += static_cast<char>(0xC0 | (cp >> 6));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        } else if (cp < 0x10000) {
            out += static_cast<char>(0xE0 | (cp >> 12));
            out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        } else {
            out += static_cast<char>(0xF0 | (cp >> 18));
            out += static_cast<char>(0x80 | ((cp >> 12) & 0x3F));
            out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        }
    }
    return out;
}

}  // namespace sz
