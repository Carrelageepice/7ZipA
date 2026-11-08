// Full 7-Zip engine glue. Modeled on 7-Zip's own CPP/7zip/UI/Client7z example,
// but format-agnostic (formats are discovered through the library's exports).

#include "engine.h"

#include <cerrno>
#include <cstdio>
#include <algorithm>
#include <set>
#include <cstring>
#include <mutex>

#include <dirent.h>

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include "path_utils.h"

#include "Common/MyWindows.h"
#include "Common/MyCom.h"
#include "Common/MyString.h"
#include "Common/StringConvert.h"
#include "Common/UTFConvert.h"
#include "Windows/PropVariant.h"
#include "7zip/Archive/IArchive.h"
#include "7zip/ICoder.h"
#include "7zip/IPassword.h"
#include "7zip/IStream.h"
#include "7zip/PropID.h"

// Exported by 7-Zip's Archive/ArchiveExports.cpp + DllExports2.cpp (compiled
// into this library, so they are plain function calls).
STDAPI GetNumberOfFormats(UINT32 *numFormats);
STDAPI GetHandlerProperty2(UInt32 formatIndex, PROPID propID, PROPVARIANT *value);
STDAPI CreateObject(const GUID *clsid, const GUID *iid, void **outObject);

namespace sz {
namespace {

void InitOnce() {
    static std::once_flag once;
    std::call_once(once, [] {
        // Bionic and glibc are UTF-8 everywhere; don't rely on locale probing.
        g_ForceToUTF8 = true;
    });
}

std::string ToUtf8(const UString &s) {
    AString a;
    ConvertUnicodeToUTF8(s, a);
    return std::string(a.Ptr(), a.Len());
}

UString FromUtf8(const std::string &s) {
    UString u;
    AString a(s.c_str());
    ConvertUTF8ToUnicode(a, u);
    return u;
}

// ---------------------------------------------------------------- streams

Z7_CLASS_IMP_IInStream(CFdInStream)
public:
    int fd = -1;
    ~CFdInStream() { if (fd >= 0) ::close(fd); }
};

Z7_COM7F_IMF(CFdInStream::Read(void *data, UInt32 size, UInt32 *processedSize)) {
    if (processedSize) *processedSize = 0;
    for (;;) {
        const ssize_t n = ::read(fd, data, size);
        if (n < 0) {
            if (errno == EINTR) continue;
            return E_FAIL;
        }
        if (processedSize) *processedSize = static_cast<UInt32>(n);
        return S_OK;
    }
}

Z7_COM7F_IMF(CFdInStream::Seek(Int64 offset, UInt32 seekOrigin, UInt64 *newPosition)) {
    int whence;
    switch (seekOrigin) {
        case STREAM_SEEK_SET: whence = SEEK_SET; break;
        case STREAM_SEEK_CUR: whence = SEEK_CUR; break;
        case STREAM_SEEK_END: whence = SEEK_END; break;
        default: return STG_E_INVALIDFUNCTION;
    }
    const off_t r = ::lseek(fd, static_cast<off_t>(offset), whence);
    if (r < 0) return E_FAIL;
    if (newPosition) *newPosition = static_cast<UInt64>(r);
    return S_OK;
}

Z7_CLASS_IMP_COM_1(CFileOutStream, ISequentialOutStream)
public:
    FILE *f = nullptr;
    bool writeFailed = false;
    ~CFileOutStream() { CloseFile(); }
    bool CloseFile() {
        if (!f) return true;
        const bool ok = fclose(f) == 0;
        f = nullptr;
        return ok;
    }
};

Z7_COM7F_IMF(CFileOutStream::Write(const void *data, UInt32 size, UInt32 *processedSize)) {
    if (processedSize) *processedSize = 0;
    if (!f) return E_FAIL;
    const size_t n = size ? fwrite(data, 1, size, f) : 0;
    if (processedSize) *processedSize = static_cast<UInt32>(n);
    if (n != size) {
        writeFailed = true;  // disk full, most likely
        return E_FAIL;
    }
    return S_OK;
}

// -------------------------------------------------------------- callbacks

class COpenCallback Z7_final :
    public IArchiveOpenCallback,
    public ICryptoGetTextPassword,
    public CMyUnknownImp {
    Z7_IFACES_IMP_UNK_2(IArchiveOpenCallback, ICryptoGetTextPassword)
public:
    bool hasPassword = false;
    UString password;
    bool passwordAsked = false;
};

Z7_COM7F_IMF(COpenCallback::SetTotal(const UInt64 *, const UInt64 *)) { return S_OK; }
Z7_COM7F_IMF(COpenCallback::SetCompleted(const UInt64 *, const UInt64 *)) { return S_OK; }
Z7_COM7F_IMF(COpenCallback::CryptoGetTextPassword(BSTR *pw)) {
    passwordAsked = true;
    if (!hasPassword) return E_ABORT;
    return StringToBstr(password, pw);
}

class CExtractCallback Z7_final :
    public IArchiveExtractCallback,
    public ICryptoGetTextPassword,
    public CMyUnknownImp {
    Z7_IFACES_IMP_UNK_2(IArchiveExtractCallback, ICryptoGetTextPassword)
    Z7_IFACE_COM7_IMP(IProgress)

    // per-item state
    CMyComPtr<ISequentialOutStream> _outStream;
    CFileOutStream *_outSpec = nullptr;
    std::string _diskPath;
    bool _hasMTime = false;
    int64_t _mtimeSec = 0;
    long _mtimeNsec = 0;
    std::u16string _curName;
    UInt64 _total = 0;

public:
    IInArchive *archive = nullptr;
    std::string base;           // output directory, no trailing '/'
    std::string fallbackName;   // for formats whose items have no name (gz, xz...)
    ProgressFn progress = nullptr;
    void *ctx = nullptr;

    bool hasPassword = false;
    UString password;
    bool anyEncrypted = false;  // does the archive contain encrypted items?
    bool testMode = false;      // verify only: nothing is written
    bool passwordAsked = false;
    bool wrongPassword = false;
    bool cancelled = false;
    std::string lastError;

    uint32_t extracted = 0, skipped = 0, failed = 0;

    // With encrypted content, a bad password shows up as a data/CRC error
    // (7-Zip often reports it for whole folders without calling GetStream).
    void NoteFailure(Int32 opRes) {
        using namespace NArchive::NExtract::NOperationResult;
        if (opRes == kWrongPassword) wrongPassword = true;
        else if (anyEncrypted && (opRes == kDataError || opRes == kCRCError ||
                                  opRes == kUnexpectedEnd || opRes == kUnavailable))
            wrongPassword = true;
    }
};

bool GetStrProp(IInArchive *a, UInt32 i, PROPID id, UString &out) {
    NWindows::NCOM::CPropVariant p;
    if (a->GetProperty(i, id, &p) != S_OK) return false;
    if (p.vt != VT_BSTR || !p.bstrVal) return false;
    out = p.bstrVal;
    return true;
}

bool GetBoolProp(IInArchive *a, UInt32 i, PROPID id) {
    NWindows::NCOM::CPropVariant p;
    if (a->GetProperty(i, id, &p) != S_OK) return false;
    return p.vt == VT_BOOL && p.boolVal != VARIANT_FALSE;
}

bool GetU64Prop(IInArchive *a, UInt32 i, PROPID id, uint64_t &out) {
    NWindows::NCOM::CPropVariant p;
    if (a->GetProperty(i, id, &p) != S_OK) return false;
    switch (p.vt) {
        case VT_UI8: out = p.uhVal.QuadPart; return true;
        case VT_UI4: out = p.ulVal; return true;
        case VT_UI2: out = p.uiVal; return true;
        case VT_UI1: out = p.bVal; return true;
        default: return false;
    }
}

Z7_COM7F_IMF(CExtractCallback::SetTotal(UInt64 size)) {
    _total = size;
    return S_OK;
}

Z7_COM7F_IMF(CExtractCallback::SetCompleted(const UInt64 *completed)) {
    if (progress && completed &&
        !progress(ctx, *completed, _total, _curName.data(), _curName.size())) {
        cancelled = true;
        return E_ABORT;
    }
    return S_OK;
}

Z7_COM7F_IMF(CExtractCallback::GetStream(UInt32 index, ISequentialOutStream **outStream,
                                        Int32 askExtractMode)) {
    *outStream = nullptr;
    _outStream.Release();
    _outSpec = nullptr;
    _hasMTime = false;

    UString upath;
    std::string name;
    if (GetStrProp(archive, index, kpidPath, upath)) name = ToUtf8(upath);
    if (name.empty()) name = fallbackName;
    _curName = Utf8ToUtf16(name);

    if (askExtractMode != NArchive::NExtract::NAskMode::kExtract) return S_OK;

    const bool isDir = GetBoolProp(archive, index, kpidIsDir);

    std::string rel;
    if (!SanitizeRelativePath(name, rel)) {
        ++skipped;
        return S_OK;  // no stream => 7-Zip skips this item's data
    }
    const std::string full = base + "/" + rel;

    if (isDir) {
        if (!MakeDirs(full)) {
            lastError = "Could not create folder: " + rel;
            return E_FAIL;
        }
        return S_OK;
    }

    {   // Links (tar, zip, 7z with -snl): never recreate them.
        UString link;
        if ((GetStrProp(archive, index, kpidSymLink, link) && !link.IsEmpty()) ||
            (GetStrProp(archive, index, kpidHardLink, link) && !link.IsEmpty())) {
            ++skipped;
            return S_OK;
        }
    }

    {   // Never create symlinks (they could point outside the output folder).
        NWindows::NCOM::CPropVariant p;
        if (archive->GetProperty(index, kpidAttrib, &p) == S_OK && p.vt == VT_UI4 &&
            (p.ulVal & 0x8000u) != 0 && ((p.ulVal >> 16) & 0170000u) == 0120000u) {
            ++skipped;
            return S_OK;
        }
    }

    {
        NWindows::NCOM::CPropVariant p;
        if (archive->GetProperty(index, kpidMTime, &p) == S_OK && p.vt == VT_FILETIME) {
            const UInt64 ft = (static_cast<UInt64>(p.filetime.dwHighDateTime) << 32) |
                              p.filetime.dwLowDateTime;
            const int64_t secs = static_cast<int64_t>(ft / 10000000ULL) - 11644473600LL;
            if (secs > 0) {
                _hasMTime = true;
                _mtimeSec = secs;
                _mtimeNsec = static_cast<long>((ft % 10000000ULL) * 100);
            }
        }
    }

    std::string parent;
    if (ParentDirOf(full, parent) && !MakeDirs(parent)) {
        lastError = "Could not create folder for: " + rel;
        return E_FAIL;
    }

    FILE *f = fopen(full.c_str(), "wb");
    if (!f) {
        lastError = "Could not write: " + rel;
        return E_FAIL;
    }
    setvbuf(f, nullptr, _IOFBF, 1 << 18);

    _outSpec = new CFileOutStream;
    CMyComPtr<ISequentialOutStream> local(_outSpec);
    _outSpec->f = f;
    _outStream = local;
    _diskPath = full;
    *outStream = local.Detach();
    return S_OK;
}

Z7_COM7F_IMF(CExtractCallback::PrepareOperation(Int32)) { return S_OK; }

Z7_COM7F_IMF(CExtractCallback::SetOperationResult(Int32 opRes)) {
    if (_outSpec) {
        const bool closed = _outSpec->CloseFile();
        const bool writeFailed = _outSpec->writeFailed || !closed;

        if (writeFailed) {
            lastError = "Write failed (disk full?).";
            unlink(_diskPath.c_str());
            _outStream.Release();
            _outSpec = nullptr;
            return E_FAIL;
        }

        if (opRes == NArchive::NExtract::NOperationResult::kOK) {
            ++extracted;
            if (_hasMTime) {
                struct timespec ts[2];
                ts[0].tv_sec = ts[1].tv_sec = static_cast<time_t>(_mtimeSec);
                ts[0].tv_nsec = ts[1].tv_nsec = _mtimeNsec;
                utimensat(AT_FDCWD, _diskPath.c_str(), ts, 0);
            }
        } else {
            ++failed;
            NoteFailure(opRes);
            unlink(_diskPath.c_str());  // don't leave corrupt partial files
        }
        _outStream.Release();
        _outSpec = nullptr;
    } else if (opRes != NArchive::NExtract::NOperationResult::kOK) {
        ++failed;
        NoteFailure(opRes);
    } else if (testMode) {
        ++extracted;  // in test mode: items verified OK
    }
    return S_OK;
}

Z7_COM7F_IMF(CExtractCallback::CryptoGetTextPassword(BSTR *pw)) {
    passwordAsked = true;
    if (!hasPassword) return E_ABORT;
    return StringToBstr(password, pw);
}

// ----------------------------------------------------------- format lookup

struct FormatInfo {
    std::string name;
    GUID clsid;
    std::vector<std::string> exts;
    bool hasSignature = false;
};

std::string AsciiLower(const UString &u) {
    std::string s;
    for (unsigned i = 0; i < u.Len(); ++i) {
        wchar_t c = u[i];
        if (c >= L'A' && c <= L'Z') c = static_cast<wchar_t>(c - L'A' + L'a');
        s += (c < 128) ? static_cast<char>(c) : '?';
    }
    return s;
}

std::vector<FormatInfo> LoadFormats() {
    std::vector<FormatInfo> out;
    UINT32 n = 0;
    if (GetNumberOfFormats(&n) != S_OK) return out;
    for (UINT32 i = 0; i < n; ++i) {
        FormatInfo f;
        {
            NWindows::NCOM::CPropVariant p;
            if (GetHandlerProperty2(i, NArchive::NHandlerPropID::kClassID, &p) != S_OK ||
                p.vt != VT_BSTR || ::SysStringByteLen(p.bstrVal) != sizeof(GUID))
                continue;
            memcpy(&f.clsid, p.bstrVal, sizeof(GUID));
        }
        {
            NWindows::NCOM::CPropVariant p;
            if (GetHandlerProperty2(i, NArchive::NHandlerPropID::kName, &p) == S_OK &&
                p.vt == VT_BSTR)
                f.name = AsciiLower(UString(p.bstrVal));
        }
        {
            NWindows::NCOM::CPropVariant p;
            if (GetHandlerProperty2(i, NArchive::NHandlerPropID::kExtension, &p) == S_OK &&
                p.vt == VT_BSTR) {
                const std::string e = AsciiLower(UString(p.bstrVal));
                size_t pos = 0;
                while (pos < e.size()) {
                    size_t sp = e.find(' ', pos);
                    if (sp == std::string::npos) sp = e.size();
                    if (sp > pos) f.exts.push_back(e.substr(pos, sp - pos));
                    pos = sp + 1;
                }
            }
        }
        for (const UInt32 id : {static_cast<UInt32>(NArchive::NHandlerPropID::kSignature),
                                static_cast<UInt32>(NArchive::NHandlerPropID::kMultiSignature)}) {
            NWindows::NCOM::CPropVariant p;
            if (GetHandlerProperty2(i, id, &p) == S_OK && p.vt == VT_BSTR &&
                ::SysStringByteLen(p.bstrVal) > 0)
                f.hasSignature = true;
        }
        out.push_back(f);
    }
    return out;
}

std::string BaseName(const std::string &hint) {
    const size_t s = hint.find_last_of("/\\");
    return s == std::string::npos ? hint : hint.substr(s + 1);
}

std::string LowerExt(const std::string &hint) {
    const std::string b = BaseName(hint);
    const size_t d = b.rfind('.');
    if (d == std::string::npos || d + 1 >= b.size()) return "";
    std::string e = b.substr(d + 1);
    for (char &c : e) if (c >= 'A' && c <= 'Z') c = static_cast<char>(c - 'A' + 'a');
    return e;
}

// "photos.tar.gz" -> "photos.tar", "data.gz" -> "data"; used for single-file
// formats whose items carry no name.
std::string DefaultItemName(const std::string &hint) {
    std::string b = BaseName(hint);
    const size_t d = b.rfind('.');
    if (d != std::string::npos && d > 0) b = b.substr(0, d);
    return b.empty() ? "content" : b;
}

struct Opened {
    CMyComPtr<IInArchive> archive;
    CMyComPtr<IInStream> stream;
    std::string format;
};

Status OpenArchive(const std::string &path, const std::string &nameHint,
                   const std::string *password, Opened &out, std::string &error) {
    InitOnce();

    int fd = -1;
    if (path.compare(0, 3, "fd:") == 0) {
        char *end = nullptr;
        const long n = strtol(path.c_str() + 3, &end, 10);
        if (end != path.c_str() + 3 && *end == '\0' && n >= 0) fd = ::dup(static_cast<int>(n));
    } else {
        fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
    }
    if (fd < 0) {
        error = "Could not open the archive file.";
        return Status::Error;
    }

    CFdInStream *spec = new CFdInStream;
    out.stream = spec;
    spec->fd = fd;

    const std::vector<FormatInfo> formats = LoadFormats();
    const std::string ext = LowerExt(nameHint);

    std::vector<const FormatInfo *> byExt, bySig;
    for (const FormatInfo &f : formats) {
        bool extMatch = false;
        for (const std::string &e : f.exts) if (!ext.empty() && e == ext) extMatch = true;
        if (extMatch) byExt.push_back(&f);
        else if (f.hasSignature) bySig.push_back(&f);
    }

    COpenCallback *cbSpec = new COpenCallback;
    CMyComPtr<IArchiveOpenCallback> cb(cbSpec);
    if (password) {
        cbSpec->hasPassword = true;
        cbSpec->password = FromUtf8(*password);
    }

    auto attempt = [&](const FormatInfo &f, UInt64 maxCheck) -> bool {
        UInt64 pos;
        if (out.stream->Seek(0, STREAM_SEEK_SET, &pos) != S_OK) return false;
        out.archive.Release();
        if (CreateObject(&f.clsid, &IID_IInArchive, (void **)&out.archive) != S_OK || !out.archive)
            return false;
        if (out.archive->Open(out.stream, &maxCheck, cb) == S_OK) {
            out.format = f.name;
            return true;
        }
        out.archive.Release();
        return false;
    };

    for (const FormatInfo *f : byExt) {
        if (attempt(*f, 1 << 22)) return Status::Ok;
        if (cbSpec->passwordAsked)
            return password ? Status::WrongPassword : Status::PasswordRequired;
    }
    for (const FormatInfo *f : bySig) {
        if (attempt(*f, 0)) return Status::Ok;
        if (cbSpec->passwordAsked)
            return password ? Status::WrongPassword : Status::PasswordRequired;
    }

    error = "Unknown or unsupported archive format.";
    return Status::Unsupported;
}


// ------------------------------------------------------------ archive creation

Z7_CLASS_IMP_COM_1(CFdOutStream, IOutStream)
    Z7_IFACE_COM7_IMP(ISequentialOutStream)
public:
    int fd = -1;
    bool failed = false;
    ~CFdOutStream() { if (fd >= 0) ::close(fd); }
};

Z7_COM7F_IMF(CFdOutStream::Write(const void *data, UInt32 size, UInt32 *processedSize)) {
    if (processedSize) *processedSize = 0;
    const char *p = static_cast<const char *>(data);
    UInt32 done = 0;
    while (done < size) {
        const ssize_t n = ::write(fd, p + done, size - done);
        if (n < 0) {
            if (errno == EINTR) continue;
            failed = true;  // disk full, most likely
            if (processedSize) *processedSize = done;
            return E_FAIL;
        }
        done += static_cast<UInt32>(n);
    }
    if (processedSize) *processedSize = done;
    return S_OK;
}

Z7_COM7F_IMF(CFdOutStream::Seek(Int64 offset, UInt32 seekOrigin, UInt64 *newPosition)) {
    int whence;
    switch (seekOrigin) {
        case STREAM_SEEK_SET: whence = SEEK_SET; break;
        case STREAM_SEEK_CUR: whence = SEEK_CUR; break;
        case STREAM_SEEK_END: whence = SEEK_END; break;
        default: return STG_E_INVALIDFUNCTION;
    }
    const off_t r = ::lseek(fd, static_cast<off_t>(offset), whence);
    if (r < 0) return E_FAIL;
    if (newPosition) *newPosition = static_cast<UInt64>(r);
    return S_OK;
}

Z7_COM7F_IMF(CFdOutStream::SetSize(UInt64 newSize)) {
    return ::ftruncate(fd, static_cast<off_t>(newSize)) == 0 ? S_OK : E_FAIL;
}

struct DirItem {
    std::string rel;     // name inside the archive, '/' separated
    std::string full;    // path on disk
    bool isDir = false;
    uint64_t size = 0;
    UInt64 fileTime = 0; // FILETIME: 100 ns ticks since 1601-01-01
    uint32_t mode = 0;   // st_mode
};

// Depth-first, directories before their children, names sorted so that the
// archive layout is deterministic.
bool WalkDir(const std::string &fullDir, const std::string &relDir,
             std::vector<DirItem> &out, std::string &error, int depth) {
    if (depth > 64) { error = "Folders are nested too deeply."; return false; }
    DIR *d = opendir(fullDir.c_str());
    if (!d) { error = "Could not read a folder."; return false; }
    std::vector<std::string> names;
    while (const dirent *e = readdir(d)) {
        const char *n = e->d_name;
        if (!strcmp(n, ".") || !strcmp(n, "..")) continue;
        names.emplace_back(n);
    }
    closedir(d);
    std::sort(names.begin(), names.end());

    for (const std::string &name : names) {
        DirItem it;
        it.full = fullDir + "/" + name;
        it.rel = relDir.empty() ? name : relDir + "/" + name;
        struct stat st;
        if (lstat(it.full.c_str(), &st) != 0) continue;
        if (S_ISLNK(st.st_mode)) continue;               // never follow/store links
        if (!S_ISREG(st.st_mode) && !S_ISDIR(st.st_mode)) continue;
        it.isDir = S_ISDIR(st.st_mode);
        it.size = it.isDir ? 0 : static_cast<uint64_t>(st.st_size);
        it.mode = st.st_mode;
        it.fileTime = (static_cast<UInt64>(st.st_mtime) + 11644473600ULL) * 10000000ULL +
                      static_cast<UInt64>(st.st_mtim.tv_nsec) / 100;
        out.push_back(it);
        if (it.isDir && !WalkDir(it.full, it.rel, out, error, depth + 1)) return false;
    }
    return true;
}

class CUpdateCallback Z7_final :
    public IArchiveUpdateCallback,
    public ICryptoGetTextPassword2,
    public CMyUnknownImp {
    Z7_IFACES_IMP_UNK_2(IArchiveUpdateCallback, ICryptoGetTextPassword2)
    Z7_IFACE_COM7_IMP(IProgress)

    std::u16string _curName;
    UInt64 _total = 0;

public:
    const std::vector<DirItem> *items = nullptr;
    ProgressFn progress = nullptr;
    void *ctx = nullptr;
    bool hasPassword = false;
    UString password;
    bool cancelled = false;
    uint32_t unreadable = 0;
};

Z7_COM7F_IMF(CUpdateCallback::SetTotal(UInt64 size)) { _total = size; return S_OK; }

Z7_COM7F_IMF(CUpdateCallback::SetCompleted(const UInt64 *completed)) {
    if (progress && completed &&
        !progress(ctx, *completed, _total, _curName.data(), _curName.size())) {
        cancelled = true;
        return E_ABORT;
    }
    return S_OK;
}

Z7_COM7F_IMF(CUpdateCallback::GetUpdateItemInfo(UInt32, Int32 *newData, Int32 *newProps,
                                               UInt32 *indexInArchive)) {
    if (newData) *newData = 1;
    if (newProps) *newProps = 1;
    if (indexInArchive) *indexInArchive = static_cast<UInt32>(-1);
    return S_OK;
}

Z7_COM7F_IMF(CUpdateCallback::GetProperty(UInt32 index, PROPID propID, PROPVARIANT *value)) {
    NWindows::NCOM::CPropVariant prop;
    if (propID == kpidIsAnti) {
        prop = false;
    } else if (index < items->size()) {
        const DirItem &di = (*items)[index];
        switch (propID) {
            case kpidPath: prop = FromUtf8(di.rel); break;
            case kpidIsDir: prop = di.isDir; break;
            case kpidSize: prop = static_cast<UInt64>(di.size); break;
            case kpidAttrib:
                // Windows attribute + Unix mode in the high 16 bits
                // (FILE_ATTRIBUTE_UNIX_EXTENSION = 0x8000), as p7zip/7-Zip do.
                prop = static_cast<UInt32>((di.isDir ? 0x10u : 0x20u) | 0x8000u |
                                           ((di.mode & 0xFFFFu) << 16));
                break;
            case kpidPosixAttrib: prop = static_cast<UInt32>(di.mode); break;
            case kpidMTime: {
                FILETIME ft;
                ft.dwLowDateTime = static_cast<DWORD>(di.fileTime & 0xFFFFFFFFu);
                ft.dwHighDateTime = static_cast<DWORD>(di.fileTime >> 32);
                prop = ft;
                break;
            }
            default: break;
        }
    }
    prop.Detach(value);
    return S_OK;
}

Z7_COM7F_IMF(CUpdateCallback::GetStream(UInt32 index, ISequentialInStream **inStream)) {
    *inStream = nullptr;
    if (index >= items->size()) return E_INVALIDARG;
    const DirItem &di = (*items)[index];
    _curName = Utf8ToUtf16(di.rel);
    if (di.isDir) return S_OK;

    const int fd = ::open(di.full.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        ++unreadable;
        return S_FALSE;  // 7-Zip skips this file and reports it in SetOperationResult
    }
    CFdInStream *spec = new CFdInStream;
    CMyComPtr<ISequentialInStream> in(spec);
    spec->fd = fd;
    *inStream = in.Detach();
    return S_OK;
}

Z7_COM7F_IMF(CUpdateCallback::SetOperationResult(Int32)) { return S_OK; }

Z7_COM7F_IMF(CUpdateCallback::CryptoGetTextPassword2(Int32 *passwordIsDefined, BSTR *pw)) {
    *passwordIsDefined = hasPassword ? 1 : 0;
    if (!hasPassword) return S_OK;
    return StringToBstr(password, pw);
}


// -------------------------------------------- temp dir, nested tar, extraction core

std::string g_tempDir;  // set by SetTempDir(); used to unwrap .tar.gz etc.

void RemoveTree(const std::string &path) {
    struct stat st;
    if (lstat(path.c_str(), &st) != 0) return;
    if (S_ISDIR(st.st_mode)) {
        if (DIR *d = opendir(path.c_str())) {
            std::vector<std::string> names;
            while (const dirent *e = readdir(d)) {
                const char *n = e->d_name;
                if (strcmp(n, ".") && strcmp(n, "..")) names.emplace_back(n);
            }
            closedir(d);
            for (const std::string &n : names) RemoveTree(path + "/" + n);
        }
        rmdir(path.c_str());
    } else {
        unlink(path.c_str());
    }
}

// Deletes its temp directory when destroyed.
struct TempDir {
    std::string path;
    ~TempDir() { if (!path.empty()) RemoveTree(path); }
};

std::string LowerStr(std::string s) {
    for (char &c : s) if (c >= 'A' && c <= 'Z') c = static_cast<char>(c - 'A' + 'a');
    return s;
}

bool EndsWith(const std::string &s, const char *suffix) {
    const size_t n = strlen(suffix);
    return s.size() >= n && s.compare(s.size() - n, n, suffix) == 0;
}

bool HintLooksLikeCompressedTar(const std::string &hint) {
    const std::string h = LowerStr(hint);
    return EndsWith(h, ".tar.gz") || EndsWith(h, ".tgz") || EndsWith(h, ".tar.xz") ||
           EndsWith(h, ".txz") || EndsWith(h, ".tar.bz2") || EndsWith(h, ".tbz2") ||
           EndsWith(h, ".tbz") || EndsWith(h, ".tar.z") || EndsWith(h, ".taz");
}

HRESULT RunExtractRaw(Opened &o, CExtractCallback *cbSpec, const std::vector<UInt32> *indices,
                      bool testMode);

// An opened archive, possibly the tar hidden inside a .tar.gz/.xz/.bz2.
struct Resolved {
    Opened opened;
    TempDir temp;           // holds the decompressed inner tar, if any
    std::string hint;       // name hint for the archive actually opened
    bool unwrapped = false;
};

// Decompresses the single .tar inside a gzip/xz/bzip2 stream into a temp dir.
bool TryUnwrapTar(Opened &outer, const std::string &hint, Resolved &out) {
    if (g_tempDir.empty()) return false;
    if (outer.format != "gzip" && outer.format != "xz" && outer.format != "bzip2") return false;

    UInt32 n = 0;
    if (outer.archive->GetNumberOfItems(&n) != S_OK || n != 1) return false;
    UString p;
    std::string name;
    if (GetStrProp(outer.archive, 0, kpidPath, p)) name = ToUtf8(p);
    const bool innerIsTar = EndsWith(LowerStr(name), ".tar");
    if (!innerIsTar && !HintLooksLikeCompressedTar(hint)) return false;

    char tmpl[4096];
    snprintf(tmpl, sizeof(tmpl), "%s/unwrap_XXXXXX", g_tempDir.c_str());
    if (!mkdtemp(tmpl)) return false;
    out.temp.path = tmpl;

    CExtractCallback *cbSpec = new CExtractCallback;
    CMyComPtr<IArchiveExtractCallback> cb(cbSpec);
    cbSpec->archive = outer.archive;
    cbSpec->base = out.temp.path;
    std::string fallback = DefaultItemName(hint);
    if (!EndsWith(LowerStr(fallback), ".tar")) fallback += ".tar";
    cbSpec->fallbackName = fallback;
    if (RunExtractRaw(outer, cbSpec, nullptr, false) != S_OK || cbSpec->extracted != 1) {
        out.temp = TempDir();
        return false;
    }

    // Find the one regular file we just wrote.
    std::string inner;
    if (DIR *d = opendir(out.temp.path.c_str())) {
        while (const dirent *e = readdir(d)) {
            const char *nm = e->d_name;
            if (strcmp(nm, ".") && strcmp(nm, "..")) inner = out.temp.path + "/" + nm;
        }
        closedir(d);
    }
    if (inner.empty()) return false;

    Opened tar;
    std::string err;
    if (OpenArchive(inner, "inner.tar", nullptr, tar, err) != Status::Ok) {
        out.temp = TempDir();  // not actually a tar: keep the outer archive
        return false;
    }
    out.opened = tar;
    out.hint = "inner.tar";
    out.unwrapped = true;
    return true;
}

Status OpenResolved(const std::string &path, const std::string &hint,
                    const std::string *password, Resolved &r, std::string &error) {
    Opened o;
    const Status st = OpenArchive(path, hint, password, o, error);
    if (st != Status::Ok) return st;
    r.hint = hint;
    if (TryUnwrapTar(o, hint, r)) {
        o.archive->Close();
        return Status::Ok;
    }
    r.opened = o;
    return Status::Ok;
}

HRESULT RunExtractRaw(Opened &o, CExtractCallback *cbSpec, const std::vector<UInt32> *indices,
                      bool testMode) {
    CMyComPtr<IArchiveExtractCallback> cb(cbSpec);
    cbSpec->testMode = testMode;
    {
        UInt32 n = 0;
        o.archive->GetNumberOfItems(&n);
        for (UInt32 i = 0; i < n && !cbSpec->anyEncrypted; ++i)
            cbSpec->anyEncrypted = GetBoolProp(o.archive, i, kpidEncrypted);
    }
    const UInt32 *ids = indices ? indices->data() : nullptr;
    const UInt32 count = indices ? static_cast<UInt32>(indices->size())
                                 : static_cast<UInt32>(-1);
    return o.archive->Extract(ids, count, testMode ? 1 : 0, cb);
}

// Selected indices plus, for selected folders, everything below them.
std::vector<UInt32> ExpandSelection(IInArchive *a, const std::vector<uint32_t> &selected) {
    UInt32 n = 0;
    a->GetNumberOfItems(&n);
    std::set<UInt32> out;
    std::vector<std::string> dirPrefixes;
    for (const uint32_t i : selected) {
        if (i >= n) continue;
        out.insert(i);
        if (GetBoolProp(a, i, kpidIsDir)) {
            UString p;
            if (GetStrProp(a, i, kpidPath, p)) {
                std::string s = ToUtf8(p);
                for (char &c : s) if (c == '\\') c = '/';
                while (!s.empty() && s.back() == '/') s.pop_back();
                if (!s.empty()) dirPrefixes.push_back(s + "/");
            }
        }
    }
    if (!dirPrefixes.empty()) {
        for (UInt32 j = 0; j < n; ++j) {
            UString p;
            if (!GetStrProp(a, j, kpidPath, p)) continue;
            std::string s = ToUtf8(p);
            for (char &c : s) if (c == '\\') c = '/';
            for (const std::string &d : dirPrefixes) {
                if (s.compare(0, d.size(), d) == 0) { out.insert(j); break; }
            }
        }
    }
    return std::vector<UInt32>(out.begin(), out.end());
}

ExtractResult ExtractCore(const std::string &path, const std::string &nameHint,
                          const std::string *password, const std::string &outDir,
                          const std::vector<uint32_t> *selected, bool testMode,
                          ProgressFn progress, void *ctx) {
    ExtractResult res;
    InitOnce();

    std::string base = outDir;
    if (!testMode) {
        while (base.size() > 1 && base.back() == '/') base.pop_back();
        if (!MakeDirs(base)) {
            res.error = "Could not create the output folder.";
            return res;
        }
    }

    Resolved r;
    res.status = OpenResolved(path, nameHint, password, r, res.error);
    if (res.status != Status::Ok) {
        if (res.status == Status::PasswordRequired) res.error = "This archive is password protected.";
        if (res.status == Status::WrongPassword) res.error = "Wrong password.";
        return res;
    }

    CExtractCallback *cbSpec = new CExtractCallback;
    CMyComPtr<IArchiveExtractCallback> keepAlive(cbSpec);
    cbSpec->archive = r.opened.archive;
    cbSpec->base = base;
    cbSpec->fallbackName = DefaultItemName(r.hint);
    cbSpec->progress = progress;
    cbSpec->ctx = ctx;
    if (password) {
        cbSpec->hasPassword = true;
        cbSpec->password = FromUtf8(*password);
    }

    std::vector<UInt32> ids;
    if (selected) {
        ids = ExpandSelection(r.opened.archive, *selected);
        if (ids.empty()) {
            res.status = Status::Error;
            res.error = "Nothing selected.";
            return res;
        }
    }

    const HRESULT hr = RunExtractRaw(r.opened, cbSpec, selected ? &ids : nullptr, testMode);
    r.opened.archive->Close();

    res.extracted = cbSpec->extracted;
    res.skipped = cbSpec->skipped;
    res.failed = cbSpec->failed;

    if (cbSpec->cancelled) {
        res.status = Status::Cancelled;
    } else if (cbSpec->passwordAsked && !cbSpec->hasPassword) {
        res.status = Status::PasswordRequired;
        res.error = "This archive is password protected.";
    } else if (cbSpec->wrongPassword) {
        res.status = Status::WrongPassword;
        res.error = "Wrong password (or the archive is damaged).";
    } else if (hr != S_OK) {
        res.status = Status::Error;
        res.error = cbSpec->lastError.empty() ? "Extraction failed." : cbSpec->lastError;
    } else {
        res.status = Status::Ok;
        if (progress) progress(ctx, 1, 1, nullptr, 0);
    }
    return res;
}

}  // namespace

// ------------------------------------------------------------------ public

static CreateResult CreateArchiveImpl(const std::string &outPath, const std::string &rootDir,
                           const CreateOptions &opt, ProgressFn progress, void *ctx) {
    CreateResult res;
    InitOnce();

    const bool single = opt.format == "gzip" || opt.format == "xz" || opt.format == "bzip2";
    if (opt.format != "7z" && opt.format != "zip" && opt.format != "tar" && !single) {
        res.status = Status::Unsupported;
        res.error = "Unsupported output format.";
        return res;
    }

    const FormatInfo *format = nullptr;
    const std::vector<FormatInfo> formats = LoadFormats();
    for (const FormatInfo &f : formats) if (f.name == opt.format) format = &f;
    if (!format) { res.error = "Format not available in this build."; return res; }

    std::vector<DirItem> items;
    if (!opt.sources.empty()) {
        // Explicit files/folders (each stored under its own name).
        std::set<std::string> seen;
        for (const std::string &raw : opt.sources) {
            std::string p = raw;
            while (p.size() > 1 && p.back() == '/') p.pop_back();
            const size_t slash = p.find_last_of('/');
            const std::string name = slash == std::string::npos ? p : p.substr(slash + 1);
            struct stat st;
            if (name.empty() || lstat(p.c_str(), &st) != 0) { ++res.unreadable; continue; }
            if (S_ISLNK(st.st_mode) || (!S_ISREG(st.st_mode) && !S_ISDIR(st.st_mode))) {
                ++res.unreadable;
                continue;
            }
            if (!seen.insert(name).second) {
                res.error = "Two items have the same name: " + name;
                return res;
            }
            DirItem it;
            it.full = p;
            it.rel = name;
            it.isDir = S_ISDIR(st.st_mode);
            it.size = it.isDir ? 0 : static_cast<uint64_t>(st.st_size);
            it.mode = st.st_mode;
            it.fileTime = (static_cast<UInt64>(st.st_mtime) + 11644473600ULL) * 10000000ULL +
                          static_cast<UInt64>(st.st_mtim.tv_nsec) / 100;
            items.push_back(it);
            if (it.isDir && !WalkDir(it.full, it.rel, items, res.error, 1)) return res;
        }
    } else {
        std::string base = rootDir;
        while (base.size() > 1 && base.back() == '/') base.pop_back();
        if (!WalkDir(base, "", items, res.error, 0)) return res;
    }
    if (items.empty()) { res.error = "Nothing to archive."; return res; }

    CMyComPtr<IOutArchive> out;
    if (CreateObject(&format->clsid, &IID_IOutArchive, (void **)&out) != S_OK || !out) {
        res.error = "This format cannot create archives.";
        return res;
    }

    const bool hasPw = opt.password && !opt.password->empty() && opt.format != "tar";

    if (hasPw && opt.format == "zip") {
        // 7-Zip's ZIP writer only accepts printable ASCII passwords (so that
        // other ZIP tools can open the file) and at most 99 characters.
        bool simple = opt.password->size() <= 99;
        for (const unsigned char c : *opt.password) {
            if (c < 0x20 || c > 0x7E) simple = false;
        }
        if (!simple) {
            res.error = "ZIP passwords can only use basic keyboard characters "
                        "(no accents or emoji) and at most 99 characters. "
                        "Use the 7z format for other characters.";
            return res;
        }
    }

    // Properties. tar accepts none.
    if (opt.format != "tar") {
        std::vector<const wchar_t *> names;
        std::vector<NWindows::NCOM::CPropVariant> values;
        int level = opt.level < 0 ? 0 : (opt.level > 9 ? 9 : opt.level);
        names.push_back(L"x");
        values.push_back(NWindows::NCOM::CPropVariant(static_cast<UInt32>(level)));
        if (opt.format == "7z" || opt.format == "zip") {
            static const char *const k7[] = {"lzma2", "lzma", "ppmd", "bzip2", "deflate", "copy"};
            static const char *const kz[] = {"deflate", "deflate64", "bzip2", "lzma", "ppmd", "copy"};
            const std::string m = LowerStr(opt.method);
            bool ok = false;
            for (const char *c : (opt.format == "7z" ? k7 : kz)) if (m == c) ok = true;
            if (ok) {
                names.push_back(L"m");
                values.push_back(NWindows::NCOM::CPropVariant(
                    std::wstring(m.begin(), m.end()).c_str()));
            }
        }
        if (opt.format == "7z") {
            if (opt.solid == 0 || opt.solid == 1) {
                names.push_back(L"s");
                values.push_back(NWindows::NCOM::CPropVariant(opt.solid == 1));
            }
            const std::string m7 = LowerStr(opt.method);
            const bool lz = m7.empty() || m7 == "lzma2" || m7 == "lzma";
            if (lz && opt.dictMb > 0 && opt.dictMb <= 1536) {
                const std::wstring d = std::to_wstring(opt.dictMb) + L"m";
                names.push_back(L"d");
                values.push_back(NWindows::NCOM::CPropVariant(d.c_str()));
            }
        }
        if (opt.threads > 0 && opt.threads <= 64) {
            names.push_back(L"mt");
            values.push_back(NWindows::NCOM::CPropVariant(static_cast<UInt32>(opt.threads)));
        }
        if (opt.format == "zip" && hasPw) {
            names.push_back(L"em");
            values.push_back(NWindows::NCOM::CPropVariant(L"AES256"));
        }
        if (opt.format == "7z" && hasPw && opt.encryptNames) {
            names.push_back(L"he");
            values.push_back(NWindows::NCOM::CPropVariant(true));
        }
        CMyComPtr<ISetProperties> setProps;
        out->QueryInterface(IID_ISetProperties, (void **)&setProps);
        if (!setProps ||
            setProps->SetProperties(names.data(), values.data(),
                                    static_cast<UInt32>(names.size())) != S_OK) {
            res.error = "Could not apply the compression settings.";
            return res;
        }
    }

    const int fd = ::open(outPath.c_str(), O_RDWR | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (fd < 0) { res.error = "Could not create the output file."; return res; }
    CFdOutStream *outSpec = new CFdOutStream;
    CMyComPtr<IOutStream> outStream(outSpec);
    outSpec->fd = fd;

    CUpdateCallback *cbSpec = new CUpdateCallback;
    CMyComPtr<IArchiveUpdateCallback> cb(cbSpec);
    cbSpec->items = &items;
    cbSpec->progress = progress;
    cbSpec->ctx = ctx;
    if (hasPw) {
        cbSpec->hasPassword = true;
        cbSpec->password = FromUtf8(*opt.password);
    }

    const HRESULT hr = out->UpdateItems(outStream, static_cast<UInt32>(items.size()), cb);
    out.Release();
    const bool writeFailed = outSpec->failed;
    outStream.Release();  // closes the file

    res.files = static_cast<uint32_t>(std::count_if(
        items.begin(), items.end(), [](const DirItem &d) { return !d.isDir; }));
    res.unreadable = cbSpec->unreadable;

    if (cbSpec->cancelled) {
        res.status = Status::Cancelled;
    } else if (writeFailed) {
        res.error = "Write failed (disk full?).";
    } else if (cbSpec->unreadable > 0) {
        res.error = "Some files could not be read.";
    } else if (hr != S_OK) {
        res.error = "Creating the archive failed.";
    } else {
        res.status = Status::Ok;
        if (progress) progress(ctx, 1, 1, nullptr, 0);
        return res;
    }
    unlink(outPath.c_str());  // never leave a partial archive behind
    return res;
}

CreateResult CreateArchive(const std::string &outPath, const std::string &rootDir,
                           const CreateOptions &opt, ProgressFn progress, void *ctx) {
    const bool gz = opt.format == "tar.gz", xz = opt.format == "tar.xz",
               bz = opt.format == "tar.bz2";
    if (!gz && !xz && !bz) return CreateArchiveImpl(outPath, rootDir, opt, progress, ctx);

    CreateResult res;
    InitOnce();
    if (g_tempDir.empty()) { res.error = "No temporary folder configured."; return res; }

    char tmpl[4096];
    snprintf(tmpl, sizeof(tmpl), "%s/wrap_XXXXXX", g_tempDir.c_str());
    if (!mkdtemp(tmpl)) { res.error = "Could not create a temporary folder."; return res; }
    TempDir temp;
    temp.path = tmpl;

    std::string name = opt.baseName.empty() ? "archive" : opt.baseName;
    for (char &c : name) if (c == '/' || c == '\\') c = '_';
    const std::string tarPath = temp.path + "/" + name + ".tar";

    // Stage 1 covers the first half of the progress bar, stage 2 the rest.
    struct Ctx { ProgressFn fn; void *ctx; bool second; } c1{progress, ctx, false};
    auto half = [](void *p, uint64_t d, uint64_t t, const char16_t *n, size_t l) -> bool {
        Ctx *c = static_cast<Ctx *>(p);
        if (!c->fn) return true;
        if (!n) return true;  // swallow stage "done" signals
        return c->fn(c->ctx, d, t ? t * 2 : 0, n, l);
    };
    CreateOptions tarOpt;
    tarOpt.format = "tar";
    tarOpt.sources = opt.sources;
    res = CreateArchiveImpl(tarPath, rootDir, tarOpt, progress ? ProgressFn(half) : nullptr, &c1);
    if (res.status != Status::Ok) return res;
    const uint32_t files = res.files, unreadable = res.unreadable;

    const std::string stage = temp.path + "/stage";
    if (!MakeDirs(stage) || rename(tarPath.c_str(), (stage + "/" + name + ".tar").c_str()) != 0) {
        res = CreateResult();
        res.error = "Could not prepare the compression step.";
        return res;
    }
    CreateOptions co;
    co.format = gz ? "gzip" : (xz ? "xz" : "bzip2");
    co.level = opt.level < 1 ? 1 : opt.level;
    co.threads = opt.threads;
    res = CreateArchiveImpl(outPath, stage, co, nullptr, nullptr);
    if (res.status == Status::Ok) {
        res.files = files;
        res.unreadable = unreadable;
        if (progress) progress(ctx, 1, 1, nullptr, 0);
    }
    return res;
}

std::string SupportedFormats() {
    InitOnce();
    std::string s;
    for (const FormatInfo &f : LoadFormats()) {
        if (!s.empty()) s += ", ";
        s += f.name;
    }
    return s;
}

ListResult ListArchive(const std::string &path, const std::string &nameHint,
                       const std::string *password) {
    ListResult res;
    Resolved r;
    res.status = OpenResolved(path, nameHint, password, r, res.error);
    Opened &o = r.opened;
    if (res.status != Status::Ok) {
        if (res.status == Status::PasswordRequired) res.error = "This archive is password protected.";
        if (res.status == Status::WrongPassword) res.error = "Wrong password.";
        return res;
    }
    res.format = r.unwrapped ? "tar" : o.format;

    UInt32 n = 0;
    if (o.archive->GetNumberOfItems(&n) != S_OK) {
        res.status = Status::Error;
        res.error = "Could not read the archive's file list.";
        return res;
    }
    const std::string fallback = DefaultItemName(r.hint);
    res.entries.reserve(n);
    for (UInt32 i = 0; i < n; ++i) {
        EntryInfo e;
        UString p;
        std::string name;
        if (GetStrProp(o.archive, i, kpidPath, p)) name = ToUtf8(p);
        if (name.empty()) name = fallback;
        e.name = Utf8ToUtf16(name);
        e.isDir = GetBoolProp(o.archive, i, kpidIsDir);
        e.encrypted = GetBoolProp(o.archive, i, kpidEncrypted);
        uint64_t size = 0;
        if (GetU64Prop(o.archive, i, kpidSize, size)) e.size = size;
        res.entries.push_back(std::move(e));
    }
    o.archive->Close();
    res.status = Status::Ok;
    return res;
}

ExtractResult ExtractAll(const std::string &path, const std::string &nameHint,
                         const std::string *password, const std::string &outDir,
                         ProgressFn progress, void *ctx) {
    return ExtractCore(path, nameHint, password, outDir, nullptr, false, progress, ctx);
}

ExtractResult ExtractEntries(const std::string &path, const std::string &nameHint,
                             const std::string *password,
                             const std::vector<uint32_t> &indices, const std::string &outDir,
                             ProgressFn progress, void *ctx) {
    return ExtractCore(path, nameHint, password, outDir, &indices, false, progress, ctx);
}

ExtractResult TestArchive(const std::string &path, const std::string &nameHint,
                          const std::string *password, ProgressFn progress, void *ctx) {
    return ExtractCore(path, nameHint, password, std::string(), nullptr, true, progress, ctx);
}

void SetTempDir(const std::string &dir) { g_tempDir = dir; }

}  // namespace sz
