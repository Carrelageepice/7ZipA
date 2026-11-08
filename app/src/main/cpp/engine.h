// Full 7-Zip engine (all formats of 7-Zip's Format7zF library) behind a small
// JNI-free API, so it can be unit-tested on a desktop machine.
#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace sz {

enum class Status {
    Ok,
    PasswordRequired,   // archive (or its headers) is encrypted, no password given
    WrongPassword,
    Unsupported,        // not an archive we understand
    Cancelled,
    Error,
};

struct EntryInfo {
    std::u16string name;   // UTF-16 for JNI
    uint64_t size = 0;
    bool isDir = false;
    bool encrypted = false;
};

// All calls take:
//   path      normal file path, or "fd:N" (an open descriptor, dup()ed).
//   nameHint  original file name (e.g. "photos.zip"); used to pick the format
//             by extension and to name single-file formats like .gz.
//   password  UTF-8, or nullptr if the user gave none.

struct ListResult {
    Status status = Status::Error;
    std::string error;
    std::string format;    // e.g. "7z", "zip", "tar"
    std::vector<EntryInfo> entries;
};

ListResult ListArchive(const std::string &path, const std::string &nameHint,
                       const std::string *password);

// Return false to cancel. Byte counts are of the *unpacked* data.
using ProgressFn = bool (*)(void *ctx, uint64_t doneBytes, uint64_t totalBytes,
                            const char16_t *name, size_t len);

struct ExtractResult {
    Status status = Status::Error;
    std::string error;
    uint32_t extracted = 0;  // items written
    uint32_t skipped = 0;    // unsafe paths, symlinks
    uint32_t failed = 0;     // items with data/CRC errors
};

// Extracts everything into outDir (created if needed). Only regular files and
// folders are created: paths that could escape outDir and symlinks are skipped.
ExtractResult ExtractAll(const std::string &path, const std::string &nameHint,
                         const std::string *password, const std::string &outDir,
                         ProgressFn progress, void *ctx);

// Where nested archives (.tar.gz, .tar.xz, .tar.bz2) are decompressed to be
// opened as a tar. Call once at startup with the app's cache directory.
void SetTempDir(const std::string &dir);

// Extracts only the given item indices (as numbered in ListArchive's result).
// Selected folders include everything below them.
ExtractResult ExtractEntries(const std::string &path, const std::string &nameHint,
                             const std::string *password,
                             const std::vector<uint32_t> &indices, const std::string &outDir,
                             ProgressFn progress, void *ctx);

// Verifies every item's checksum without writing anything. In the result,
// `extracted` counts items verified OK and `failed` items with errors.
ExtractResult TestArchive(const std::string &path, const std::string &nameHint,
                          const std::string *password, ProgressFn progress, void *ctx);

struct CreateOptions {
    std::string format;                  // "7z", "zip", "tar", "tar.gz", "tar.xz" or "tar.bz2"
    int level = 5;                       // 0 (store) .. 9; ignored for tar
    const std::string *password = nullptr;
    bool encryptNames = false;           // 7z only: also hide the file names
    // Advanced (all optional). Empty / 0 / -1 mean "use the default".
    std::string method;                  // 7z: lzma2 lzma ppmd bzip2 deflate copy; zip: deflate deflate64 bzip2 lzma ppmd copy
    int solid = -1;                      // 7z: 1 = solid, 0 = not solid
    int dictMb = 0;                      // 7z: dictionary size in MB
    int threads = 0;                     // 0 = automatic
    std::string baseName;                // name of the .tar inside tar.gz/xz/bz2
    std::vector<std::string> sources;    // if set: archive exactly these paths (rootDir ignored)
};

struct CreateResult {
    Status status = Status::Error;
    std::string error;
    uint32_t files = 0;      // files added (folders not counted)
    uint32_t unreadable = 0; // files that could not be opened
};

// Packs everything under rootDir (recursively, relative names) into outPath.
// Symlinks and special files are ignored. zip + password uses AES-256.
// On failure or cancel the partial output file is deleted.
CreateResult CreateArchive(const std::string &outPath, const std::string &rootDir,
                           const CreateOptions &options, ProgressFn progress, void *ctx);

// Names of the formats this build can open, e.g. "7z, zip, rar, tar, ...".
std::string SupportedFormats();

}  // namespace sz
