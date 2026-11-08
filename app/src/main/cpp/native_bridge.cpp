#include <jni.h>

#include <chrono>
#include <mutex>
#include <string>
#include <vector>

#include "engine.h"
#include "path_utils.h"
#include "third_party/7zip/C/7zVersion.h"

namespace {

// ThrowNew wants "modified UTF-8"; keep messages ASCII so file names with
// emoji can't trip CheckJNI.
void ThrowIOException(JNIEnv *env, const std::string &message) {
    if (env->ExceptionCheck()) return;
    std::string safe = message;
    for (char &ch : safe) {
        if (static_cast<unsigned char>(ch) >= 0x80) ch = '?';
    }
    jclass cls = env->FindClass("java/io/IOException");
    if (cls != nullptr) {
        env->ThrowNew(cls, safe.c_str());
        env->DeleteLocalRef(cls);
    }
}

// Kotlin maps these prefixes to PasswordRequiredException / WrongPasswordException.
void ThrowForStatus(JNIEnv *env, sz::Status status, const std::string &error) {
    switch (status) {
        case sz::Status::PasswordRequired:
            ThrowIOException(env, "[PASSWORD_REQUIRED] " + error);
            break;
        case sz::Status::WrongPassword:
            ThrowIOException(env, "[WRONG_PASSWORD] " + error);
            break;
        default:
            ThrowIOException(env, error.empty() ? "Unknown error." : error);
    }
}

// Standard UTF-8 (not JNI's modified UTF-8) from a Java string.
std::string ToUtf8(JNIEnv *env, jstring s) {
    const jsize len = env->GetStringLength(s);
    const jchar *chars = env->GetStringChars(s, nullptr);
    if (chars == nullptr) return std::string();
    std::string out = sz::Utf16ToUtf8(reinterpret_cast<const char16_t *>(chars),
                                      static_cast<size_t>(len));
    env->ReleaseStringChars(s, chars);
    return out;
}

jstring NewJString(JNIEnv *env, const std::u16string &s) {
    return env->NewString(reinterpret_cast<const jchar *>(s.data()),
                          static_cast<jsize>(s.size()));
}

// Bridges the engine's progress callbacks to Kotlin's ExtractProgress.
//
// 7-Zip may call progress from its own worker threads, and a JNIEnv (and local
// references) are only valid on the thread they came from. So: the callback is
// held through a global reference, other threads are attached to the VM for the
// duration of the call, calls are serialised and throttled (~10/second), and an
// exception thrown by Kotlin is captured and re-thrown on the original thread
// by Finish().
class ProgressBridge {
public:
    ProgressBridge(JNIEnv *env, jobject callback) {
        env->GetJavaVM(&vm_);
        if (callback == nullptr) return;
        jclass iface = env->FindClass("org/openarchive/sevenzip/ExtractProgress");
        if (iface == nullptr) { failedSetup_ = true; return; }
        method_ = env->GetMethodID(iface, "onProgress", "(JJLjava/lang/String;)Z");
        env->DeleteLocalRef(iface);
        if (method_ == nullptr) { failedSetup_ = true; return; }
        callback_ = env->NewGlobalRef(callback);
        if (callback_ == nullptr) failedSetup_ = true;
    }

    // False if setup failed; a Java exception is then pending on `env`.
    bool ok() const { return !failedSetup_; }

    static bool Callback(void *ctx, uint64_t done, uint64_t total,
                         const char16_t *name, size_t len) {
        return static_cast<ProgressBridge *>(ctx)->Call(done, total, name, len);
    }

    // Releases references. If Kotlin threw, re-throws that exception on `env`
    // and returns true.
    bool Finish(JNIEnv *env) {
        bool thrown = false;
        if (pending_ != nullptr) {
            env->Throw(pending_);
            env->DeleteGlobalRef(pending_);
            pending_ = nullptr;
            thrown = true;
        }
        if (callback_ != nullptr) {
            env->DeleteGlobalRef(callback_);
            callback_ = nullptr;
        }
        return thrown;
    }

private:
    bool Call(uint64_t done, uint64_t total, const char16_t *name, size_t len) {
        if (callback_ == nullptr) return true;
        std::lock_guard<std::mutex> lock(mutex_);
        if (failed_) return false;

        const auto now = std::chrono::steady_clock::now();
        const bool isFinal = (name == nullptr);
        if (!isFinal && now - last_ < std::chrono::milliseconds(100)) return true;
        last_ = now;

        JNIEnv *env = nullptr;
        bool attached = false;
        const jint rc = vm_->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
        if (rc == JNI_EDETACHED) {
#ifdef __ANDROID__
            if (vm_->AttachCurrentThread(&env, nullptr) != JNI_OK) return true;
#else
            if (vm_->AttachCurrentThread(reinterpret_cast<void **>(&env), nullptr) != JNI_OK)
                return true;
#endif
            attached = true;
        } else if (rc != JNI_OK) {
            return true;
        }

        bool keepGoing = true;
        jstring jname = env->NewString(
                reinterpret_cast<const jchar *>(name ? name : u""),
                static_cast<jsize>(name ? len : 0));
        if (jname == nullptr) {
            env->ExceptionClear();
            keepGoing = false;
        } else {
            const jboolean r = env->CallBooleanMethod(
                    callback_, method_,
                    static_cast<jlong>(done), static_cast<jlong>(total), jname);
            env->DeleteLocalRef(jname);
            // The Kotlin side may deliberately sleep (CPU/heat limits). Count the
            // gap until the next call from when it returned, so the work between
            // two calls is real work.
            last_ = std::chrono::steady_clock::now();
            if (env->ExceptionCheck()) {
                jthrowable t = env->ExceptionOccurred();
                env->ExceptionClear();
                pending_ = static_cast<jthrowable>(env->NewGlobalRef(t));
                env->DeleteLocalRef(t);
                failed_ = true;
                keepGoing = false;
            } else {
                keepGoing = (r == JNI_TRUE);
            }
        }

        if (attached) vm_->DetachCurrentThread();
        return keepGoing;
    }

    JavaVM *vm_ = nullptr;
    jobject callback_ = nullptr;
    jmethodID method_ = nullptr;
    jthrowable pending_ = nullptr;
    bool failedSetup_ = false;
    bool failed_ = false;
    std::mutex mutex_;
    std::chrono::steady_clock::time_point last_{};
};

}  // namespace

extern "C"
JNIEXPORT jstring JNICALL
Java_org_openarchive_sevenzip_NativeBridge_nativeEngineStatus(
        JNIEnv *env, jobject /* thiz */) {
    const std::string s = "7-Zip " MY_VERSION_NUMBERS " engine. Formats: " +
                          sz::SupportedFormats();
    return NewJString(env, sz::Utf8ToUtf16(s));
}

// ArchiveEntry(String name, long size, boolean isDirectory, boolean encrypted)
extern "C"
JNIEXPORT jobjectArray JNICALL
Java_org_openarchive_sevenzip_NativeBridge_nativeListArchive(
        JNIEnv *env, jobject /* thiz */,
        jstring archivePath, jstring nameHint, jstring password) {

    if (archivePath == nullptr) {
        ThrowIOException(env, "Archive path is missing.");
        return nullptr;
    }
    const std::string path = ToUtf8(env, archivePath);
    const std::string hint = nameHint ? ToUtf8(env, nameHint) : std::string();
    std::string pw;
    if (password) pw = ToUtf8(env, password);

    const sz::ListResult res = sz::ListArchive(path, hint, password ? &pw : nullptr);
    if (res.status != sz::Status::Ok) {
        ThrowForStatus(env, res.status, res.error);
        return nullptr;
    }

    jclass entryClass = env->FindClass("org/openarchive/sevenzip/ArchiveEntry");
    if (entryClass == nullptr) return nullptr;
    jmethodID ctor = env->GetMethodID(entryClass, "<init>", "(Ljava/lang/String;JZZ)V");
    if (ctor == nullptr) {
        env->DeleteLocalRef(entryClass);
        return nullptr;
    }

    jobjectArray entries = env->NewObjectArray(
            static_cast<jsize>(res.entries.size()), entryClass, nullptr);
    if (entries == nullptr) {
        env->DeleteLocalRef(entryClass);
        return nullptr;
    }

    for (size_t i = 0; i < res.entries.size(); ++i) {
        const sz::EntryInfo &e = res.entries[i];
        jstring jname = NewJString(env, e.name);
        if (jname == nullptr) break;
        jobject obj = env->NewObject(entryClass, ctor, jname,
                                     static_cast<jlong>(e.size),
                                     e.isDir ? JNI_TRUE : JNI_FALSE,
                                     e.encrypted ? JNI_TRUE : JNI_FALSE);
        env->DeleteLocalRef(jname);
        if (obj == nullptr) break;
        env->SetObjectArrayElement(entries, static_cast<jsize>(i), obj);
        env->DeleteLocalRef(obj);
        if (env->ExceptionCheck()) break;
    }
    env->DeleteLocalRef(entryClass);
    return entries;
}

// Returns [extracted, skipped, failed, cancelled(0/1)]; throws IOException.
extern "C"
JNIEXPORT jintArray JNICALL
Java_org_openarchive_sevenzip_NativeBridge_nativeExtractAll(
        JNIEnv *env, jobject /* thiz */,
        jstring archivePath, jstring nameHint, jstring password,
        jstring outputDir, jobject progress) {

    if (archivePath == nullptr || outputDir == nullptr) {
        ThrowIOException(env, "Archive or output path is missing.");
        return nullptr;
    }

    ProgressBridge bridge(env, progress);
    if (!bridge.ok()) { bridge.Finish(env); return nullptr; }

    const std::string path = ToUtf8(env, archivePath);
    const std::string hint = nameHint ? ToUtf8(env, nameHint) : std::string();
    const std::string outDir = ToUtf8(env, outputDir);
    std::string pw;
    if (password) pw = ToUtf8(env, password);

    const sz::ExtractResult res = sz::ExtractAll(
            path, hint, password ? &pw : nullptr, outDir,
            ProgressBridge::Callback, &bridge);

    if (bridge.Finish(env)) return nullptr;  // exception thrown by the Kotlin callback

    if (res.status != sz::Status::Ok && res.status != sz::Status::Cancelled) {
        ThrowForStatus(env, res.status, res.error);
        return nullptr;
    }

    jintArray out = env->NewIntArray(4);
    if (out == nullptr) return nullptr;
    const jint values[4] = {
            static_cast<jint>(res.extracted), static_cast<jint>(res.skipped),
            static_cast<jint>(res.failed),
            res.status == sz::Status::Cancelled ? 1 : 0};
    env->SetIntArrayRegion(out, 0, 4, values);
    return out;
}

// Returns [files, cancelled(0/1)]; throws IOException.
// Progress uses the same ExtractProgress interface (bytes of input processed).
extern "C"
JNIEXPORT jintArray JNICALL
Java_org_openarchive_sevenzip_NativeBridge_nativeCreateArchive(
        JNIEnv *env, jobject /* thiz */,
        jstring outputPath, jstring rootDir, jstring format, jint level,
        jstring password, jboolean encryptNames, jobject progress) {

    if (outputPath == nullptr || rootDir == nullptr || format == nullptr) {
        ThrowIOException(env, "Output path, source folder or format is missing.");
        return nullptr;
    }

    ProgressBridge bridge(env, progress);
    if (!bridge.ok()) { bridge.Finish(env); return nullptr; }

    const std::string out = ToUtf8(env, outputPath);
    const std::string root = ToUtf8(env, rootDir);
    std::string pw;
    sz::CreateOptions opt;
    opt.format = ToUtf8(env, format);
    opt.level = static_cast<int>(level);
    opt.encryptNames = encryptNames == JNI_TRUE;
    if (password != nullptr) {
        pw = ToUtf8(env, password);
        opt.password = &pw;
    }

    const sz::CreateResult res = sz::CreateArchive(
            out, root, opt, ProgressBridge::Callback, &bridge);

    if (bridge.Finish(env)) return nullptr;  // exception thrown by the Kotlin callback

    if (res.status != sz::Status::Ok && res.status != sz::Status::Cancelled) {
        ThrowForStatus(env, res.status, res.error);
        return nullptr;
    }

    jintArray result = env->NewIntArray(2);
    if (result == nullptr) return nullptr;
    const jint values[2] = {static_cast<jint>(res.files),
                            res.status == sz::Status::Cancelled ? 1 : 0};
    env->SetIntArrayRegion(result, 0, 2, values);
    return result;
}

// ---------------------------------------------------------------- Rev 0.8 additions

extern "C"
JNIEXPORT void JNICALL
Java_org_openarchive_sevenzip_NativeBridge_nativeSetTempDir(JNIEnv *env, jobject, jstring dir) {
    if (dir != nullptr) sz::SetTempDir(ToUtf8(env, dir));
}

// Extracts only the listed item indices. Returns [extracted, skipped, failed, cancelled].
extern "C"
JNIEXPORT jintArray JNICALL
Java_org_openarchive_sevenzip_NativeBridge_nativeExtractEntries(
        JNIEnv *env, jobject, jstring archivePath, jstring nameHint, jstring password,
        jintArray indices, jstring outputDir, jobject progress) {

    if (archivePath == nullptr || outputDir == nullptr || indices == nullptr) {
        ThrowIOException(env, "Archive, selection or output path is missing.");
        return nullptr;
    }
    ProgressBridge bridge(env, progress);
    if (!bridge.ok()) { bridge.Finish(env); return nullptr; }

    std::vector<uint32_t> ids;
    const jsize n = env->GetArrayLength(indices);
    if (n > 0) {
        std::vector<jint> tmp(static_cast<size_t>(n));
        env->GetIntArrayRegion(indices, 0, n, tmp.data());
        for (const jint v : tmp) if (v >= 0) ids.push_back(static_cast<uint32_t>(v));
    }

    const std::string path = ToUtf8(env, archivePath);
    const std::string hint = nameHint ? ToUtf8(env, nameHint) : std::string();
    const std::string outDir = ToUtf8(env, outputDir);
    std::string pw;
    if (password) pw = ToUtf8(env, password);

    const sz::ExtractResult res = sz::ExtractEntries(
            path, hint, password ? &pw : nullptr, ids, outDir, ProgressBridge::Callback, &bridge);

    if (bridge.Finish(env)) return nullptr;
    if (res.status != sz::Status::Ok && res.status != sz::Status::Cancelled) {
        ThrowForStatus(env, res.status, res.error);
        return nullptr;
    }
    jintArray out = env->NewIntArray(4);
    if (out == nullptr) return nullptr;
    const jint values[4] = {static_cast<jint>(res.extracted), static_cast<jint>(res.skipped),
                            static_cast<jint>(res.failed),
                            res.status == sz::Status::Cancelled ? 1 : 0};
    env->SetIntArrayRegion(out, 0, 4, values);
    return out;
}

// Verifies the archive. Returns [verified, failed, cancelled].
extern "C"
JNIEXPORT jintArray JNICALL
Java_org_openarchive_sevenzip_NativeBridge_nativeTestArchive(
        JNIEnv *env, jobject, jstring archivePath, jstring nameHint, jstring password,
        jobject progress) {

    if (archivePath == nullptr) {
        ThrowIOException(env, "Archive path is missing.");
        return nullptr;
    }
    ProgressBridge bridge(env, progress);
    if (!bridge.ok()) { bridge.Finish(env); return nullptr; }

    const std::string path = ToUtf8(env, archivePath);
    const std::string hint = nameHint ? ToUtf8(env, nameHint) : std::string();
    std::string pw;
    if (password) pw = ToUtf8(env, password);

    const sz::ExtractResult res = sz::TestArchive(
            path, hint, password ? &pw : nullptr, ProgressBridge::Callback, &bridge);

    if (bridge.Finish(env)) return nullptr;
    // A failed check is a result, not an exception; only real errors throw.
    if (res.status != sz::Status::Ok && res.status != sz::Status::Cancelled &&
        res.status != sz::Status::WrongPassword) {
        ThrowForStatus(env, res.status, res.error);
        return nullptr;
    }
    jintArray out = env->NewIntArray(3);
    if (out == nullptr) return nullptr;
    const jint values[3] = {static_cast<jint>(res.extracted),
                            static_cast<jint>(res.failed + (res.status == sz::Status::WrongPassword ? 1 : 0)),
                            res.status == sz::Status::Cancelled ? 1 : 0};
    env->SetIntArrayRegion(out, 0, 3, values);
    return out;
}

// Like nativeCreateArchive with the advanced options. Returns [files, cancelled].
extern "C"
JNIEXPORT jintArray JNICALL
Java_org_openarchive_sevenzip_NativeBridge_nativeCreateArchiveEx(
        JNIEnv *env, jobject, jstring outputPath, jstring rootDir, jstring format, jint level,
        jstring password, jboolean encryptNames, jstring method, jint solid, jint dictMb,
        jint threads, jstring baseName, jobject progress) {

    if (outputPath == nullptr || rootDir == nullptr || format == nullptr) {
        ThrowIOException(env, "Output path, source folder or format is missing.");
        return nullptr;
    }
    ProgressBridge bridge(env, progress);
    if (!bridge.ok()) { bridge.Finish(env); return nullptr; }

    const std::string out = ToUtf8(env, outputPath);
    const std::string root = ToUtf8(env, rootDir);
    std::string pw;
    sz::CreateOptions opt;
    opt.format = ToUtf8(env, format);
    opt.level = static_cast<int>(level);
    opt.encryptNames = encryptNames == JNI_TRUE;
    if (password != nullptr) { pw = ToUtf8(env, password); opt.password = &pw; }
    if (method != nullptr) opt.method = ToUtf8(env, method);
    opt.solid = static_cast<int>(solid);
    opt.dictMb = static_cast<int>(dictMb);
    opt.threads = static_cast<int>(threads);
    if (baseName != nullptr) opt.baseName = ToUtf8(env, baseName);

    const sz::CreateResult res =
            sz::CreateArchive(out, root, opt, ProgressBridge::Callback, &bridge);

    if (bridge.Finish(env)) return nullptr;
    if (res.status != sz::Status::Ok && res.status != sz::Status::Cancelled) {
        ThrowForStatus(env, res.status, res.error);
        return nullptr;
    }
    jintArray result = env->NewIntArray(2);
    if (result == nullptr) return nullptr;
    const jint values[2] = {static_cast<jint>(res.files),
                            res.status == sz::Status::Cancelled ? 1 : 0};
    env->SetIntArrayRegion(result, 0, 2, values);
    return result;
}

// Creates an archive from an explicit list of files/folders (absolute paths),
// each stored under its own name. Returns [files, cancelled].
extern "C"
JNIEXPORT jintArray JNICALL
Java_org_openarchive_sevenzip_NativeBridge_nativeCreateArchiveFromPaths(
        JNIEnv *env, jobject, jstring outputPath, jobjectArray sources, jstring format,
        jint level, jstring password, jboolean encryptNames, jstring method, jint solid,
        jint dictMb, jint threads, jstring baseName, jobject progress) {

    if (outputPath == nullptr || sources == nullptr || format == nullptr) {
        ThrowIOException(env, "Output path, sources or format is missing.");
        return nullptr;
    }
    ProgressBridge bridge(env, progress);
    if (!bridge.ok()) { bridge.Finish(env); return nullptr; }

    const std::string out = ToUtf8(env, outputPath);
    std::string pw;
    sz::CreateOptions opt;
    opt.format = ToUtf8(env, format);
    opt.level = static_cast<int>(level);
    opt.encryptNames = encryptNames == JNI_TRUE;
    if (password != nullptr) { pw = ToUtf8(env, password); opt.password = &pw; }
    if (method != nullptr) opt.method = ToUtf8(env, method);
    opt.solid = static_cast<int>(solid);
    opt.dictMb = static_cast<int>(dictMb);
    opt.threads = static_cast<int>(threads);
    if (baseName != nullptr) opt.baseName = ToUtf8(env, baseName);

    const jsize n = env->GetArrayLength(sources);
    for (jsize i = 0; i < n; ++i) {
        jstring s = static_cast<jstring>(env->GetObjectArrayElement(sources, i));
        if (s == nullptr) continue;
        opt.sources.push_back(ToUtf8(env, s));
        env->DeleteLocalRef(s);
    }

    const sz::CreateResult res =
            sz::CreateArchive(out, std::string(), opt, ProgressBridge::Callback, &bridge);

    if (bridge.Finish(env)) return nullptr;
    if (res.status != sz::Status::Ok && res.status != sz::Status::Cancelled) {
        ThrowForStatus(env, res.status, res.error);
        return nullptr;
    }
    jintArray result = env->NewIntArray(2);
    if (result == nullptr) return nullptr;
    const jint values[2] = {static_cast<jint>(res.files),
                            res.status == sz::Status::Cancelled ? 1 : 0};
    env->SetIntArrayRegion(result, 0, 2, values);
    return result;
}
