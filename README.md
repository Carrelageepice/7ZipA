# 7Zip for Android

An open-source Android archive manager built around the 7-Zip engine.

AI WAS USED IN THE DEVELOPMENT OF THIS APP

## Project goals

- Native ARM64 support first (`arm64-v8a`).
- 7z creation and extraction using the official 7-Zip codebase.
- ZIP, TAR, GZIP, XZ and other formats supported by selected 7-Zip handlers.
- RAR extraction only; no RAR creation.
- Password-protected archives and encryption options.
- Material You interface with advanced compression settings.
- Android Storage Access Framework; no broad storage permission by default.
- No ads, analytics, trackers or proprietary runtime dependencies.
- F-Droid-compatible source and build process.

## Open in Android Studio

1. Install a current stable Android Studio.
2. Install Android SDK Platform 36, Android SDK Build-Tools, NDK (Side by side),
   and CMake from SDK Manager.
3. Open this directory as a project.
4. Allow Gradle sync to complete.
5. Select an ARM64 device (for example, a OnePlus 12) and run the `app` configuration.

The project currently pins Android Gradle Plugin 8.13.2, Kotlin 2.2.21,
Compose BOM 2025.10.00 and Java 17. If Android Studio recommends a compatible
Gradle/JDK update, review the change rather than blindly accepting it.

## Source layout

- `app/src/main/java/` — Kotlin application and JNI boundary.
- `app/src/main/cpp/` — C++ bridge and CMake build.
- `third_party/7zip/` — reserved for the upstream 7-Zip source (not bundled yet).

## Next engineering milestone

1. Pin an upstream 7-Zip source release and record its checksum.
2. Audit source-file licences and identify the minimal portable archive-handler
   subset. Keep the unRAR restriction and all notices intact.
3. Compile the chosen engine for Android ARM64 with the NDK.
4. Expose a small, safe JNI API for listing, testing, extracting and creating
   archives. Do not pass arbitrary shell commands through JNI.
5. Add document-picker and destination-picker flows, cancellation and progress.
6. Add tests with known-good archives and malformed/corrupt inputs.

## Licensing

The original Android application scaffold is offered under the MIT License; see
`LICENSE`. The 7-Zip engine is **not included in this scaffold** and retains
its own upstream licensing. Most 7-Zip source is LGPL; RAR decompression source
has the additional unRAR restriction. Review `third_party/7zip` notices and
the current upstream `DOC/License.txt` before redistributing a build.

Upstream:
https://github.com/ip7z/7zip

## Working identity

`org.openarchive.sevenzip` is a provisional application ID. Choose a permanent
ID before publishing, because changing it later creates a different Android app.
