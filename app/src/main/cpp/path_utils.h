// Small, dependency-free helpers shared by the engine and its tests.
#pragma once

#include <cstddef>
#include <string>

namespace sz {

// Turns an archive path into a safe relative path (components joined by '/').
// Returns false for empty names or names that try to escape (".." component,
// absolute path, drive letter, NUL). "." and empty components are dropped.
// Both '/' and '\' are treated as separators.
bool SanitizeRelativePath(const std::string &in, std::string &out);

// mkdir -p. Only call with paths built from SanitizeRelativePath results.
bool MakeDirs(const std::string &path);

// Everything before the last '/', false if there is none.
bool ParentDirOf(const std::string &path, std::string &parent);

// Decodes UTF-8 (invalid bytes become U+FFFD) into UTF-16 for JNI.
std::u16string Utf8ToUtf16(const std::string &s);

// Encodes UTF-16 as standard UTF-8 (JNI's GetStringUTFChars returns "modified
// UTF-8", which mangles emoji and other characters outside the BMP).
std::string Utf16ToUtf8(const char16_t *s, size_t len);

}  // namespace sz
