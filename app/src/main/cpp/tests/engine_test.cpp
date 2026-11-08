// Desktop test driver for the engine:
//   engine_test selftest
//   engine_test list    <archive|fd:N> <nameHint> [password]
//   engine_test extract <archive|fd:N> <nameHint> <outDir> [password]
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>

#include "../engine.h"
#include "../path_utils.h"

static const char *StatusName(sz::Status s) {
    switch (s) {
        case sz::Status::Ok: return "OK";
        case sz::Status::PasswordRequired: return "PASSWORD_REQUIRED";
        case sz::Status::WrongPassword: return "WRONG_PASSWORD";
        case sz::Status::Unsupported: return "UNSUPPORTED";
        case sz::Status::Cancelled: return "CANCELLED";
        default: return "ERROR";
    }
}

static int SelfTest() {
    struct { const char *in; bool ok; const char *out; } c[] = {
        {"a/b.txt", 1, "a/b.txt"}, {"a\\b\\c.txt", 1, "a/b/c.txt"}, {"./x//y", 1, "x/y"},
        {"../evil", 0, ""}, {"a/../../evil", 0, ""}, {"/etc/passwd", 0, ""},
        {"C:\\win", 0, ""}, {"", 0, ""}, {"..", 0, ""}, {"a/..b/c", 1, "a/..b/c"}};
    for (auto &t : c) {
        std::string o;
        const bool r = sz::SanitizeRelativePath(t.in, o);
        if (r != t.ok || (r && o != t.out)) { printf("SANITIZE FAIL: %s\n", t.in); return 1; }
    }
    puts("selftest ok");
    return 0;
}

static bool Progress(void *ctx, uint64_t done, uint64_t total, const char16_t *, size_t) {
    int *calls = static_cast<int *>(ctx);
    ++*calls;
    (void)done; (void)total;
    return true;
}

int main(int argc, char **argv) {
    if (const char *t = getenv("SZ_TEMP")) sz::SetTempDir(t);

    // select <archive> <hint> <outDir> <idx,idx,...>
    if (argc >= 6 && !strcmp(argv[1], "select")) {
        std::vector<uint32_t> ids;
        for (char *p = strtok(argv[5], ","); p; p = strtok(nullptr, ","))
            ids.push_back(static_cast<uint32_t>(atoi(p)));
        const sz::ExtractResult r =
            sz::ExtractEntries(argv[2], argv[3], nullptr, ids, argv[4], Progress, new int(0));
        printf("status=%s extracted=%u skipped=%u failed=%u err=%s\n", StatusName(r.status),
               r.extracted, r.skipped, r.failed, r.error.c_str());
        return r.status == sz::Status::Ok ? 0 : 1;
    }

    // test <archive> <hint> [password]
    if (argc >= 4 && !strcmp(argv[1], "test")) {
        std::string pw;
        const std::string *pwp = nullptr;
        if (argc >= 5) { pw = argv[4]; pwp = &pw; }
        const sz::ExtractResult r = sz::TestArchive(argv[2], argv[3], pwp, Progress, new int(0));
        printf("status=%s verified=%u failed=%u err=%s\n", StatusName(r.status), r.extracted,
               r.failed, r.error.c_str());
        return r.status == sz::Status::Ok ? 0 : 1;
    }
    if (argc >= 2 && !strcmp(argv[1], "selftest")) return SelfTest();

    if (argc >= 4 && !strcmp(argv[1], "list")) {
        std::string pw;
        const std::string *pwp = nullptr;
        if (argc >= 5) { pw = argv[4]; pwp = &pw; }
        const sz::ListResult r = sz::ListArchive(argv[2], argv[3], pwp);
        printf("status=%s format=%s entries=%zu err=%s\n", StatusName(r.status),
               r.format.c_str(), r.entries.size(), r.error.c_str());
        for (const auto &e : r.entries) {
            std::string n;
            for (char16_t ch : e.name) n += ch < 128 ? static_cast<char>(ch) : '?';
            printf("  %c%c %10llu %s\n", e.isDir ? 'D' : '-', e.encrypted ? 'E' : '-',
                   static_cast<unsigned long long>(e.size), n.c_str());
        }
        return r.status == sz::Status::Ok ? 0 : 1;
    }

    if (argc >= 5 && !strcmp(argv[1], "extract")) {
        std::string pw;
        const std::string *pwp = nullptr;
        if (argc >= 6) { pw = argv[5]; pwp = &pw; }
        int calls = 0;
        const sz::ExtractResult r =
            sz::ExtractAll(argv[2], argv[3], pwp, argv[4], Progress, &calls);
        printf("status=%s extracted=%u skipped=%u failed=%u progress_calls>0=%d err=%s\n",
               StatusName(r.status), r.extracted, r.skipped, r.failed, calls > 0,
               r.error.c_str());
        return r.status == sz::Status::Ok ? 0 : 1;
    }

    // create <outFile> <rootDir> <format> <level> [password] [encryptNames]
    if (argc >= 6 && !strcmp(argv[1], "create")) {
        sz::CreateOptions o;
        o.format = argv[4];
        o.level = atoi(argv[5]);
        std::string pw;
        if (argc >= 7) { pw = argv[6]; o.password = &pw; }
        o.encryptNames = argc >= 8;
        if (const char *e = getenv("SZ_METHOD")) o.method = e;
        if (const char *e = getenv("SZ_SOLID")) o.solid = atoi(e);
        if (const char *e = getenv("SZ_DICT")) o.dictMb = atoi(e);
        if (const char *e = getenv("SZ_THREADS")) o.threads = atoi(e);
        o.baseName = "bundle";
        int calls = 0;
        const sz::CreateResult r = sz::CreateArchive(argv[2], argv[3], o, Progress, &calls);
        printf("status=%s files=%u unreadable=%u err=%s\n", StatusName(r.status), r.files,
               r.unreadable, r.error.c_str());
        return r.status == sz::Status::Ok ? 0 : 1;
    }

    fprintf(stderr, "usage: see header comment\n");
    return 2;
}
