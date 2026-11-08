cmake_minimum_required(VERSION 3.22.1)

project(sevenzip_android LANGUAGES C CXX)

set(CMAKE_C_STANDARD 99)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# ZIP7_ENGINE_C / ZIP7_ENGINE_CPP: the same files 7-Zip's own Linux makefile
# compiles for its all-formats library (CPP/7zip/Bundles/Format7zF), no asm.
include(Z7Sources.cmake)

# One OBJECT library, linked directly into the final .so. (Not a static
# library: 7-Zip registers its formats/codecs through static initialisers that
# a static-library link would silently drop.)
add_library(z7obj OBJECT
        ${ZIP7_ENGINE_C}
        ${ZIP7_ENGINE_CPP}
        engine.cpp
        path_utils.cpp
)

target_include_directories(z7obj PUBLIC third_party/7zip/CPP)

target_compile_definitions(z7obj PUBLIC
        Z7_EXTERNAL_CODECS
        _REENTRANT
        _FILE_OFFSET_BITS=64
        _LARGEFILE_SOURCE
        NDEBUG
)

# -O2 even in Gradle "debug" builds: the codecs are unusably slow at -O0.
# -w: third-party code, warnings are 7-Zip's business.
target_compile_options(z7obj PRIVATE -O2 -w)

if (ANDROID)
    add_library(sevenzip_android SHARED
            native_bridge.cpp
            $<TARGET_OBJECTS:z7obj>
    )
    target_include_directories(sevenzip_android PRIVATE third_party/7zip/CPP)
    target_compile_definitions(sevenzip_android PRIVATE _FILE_OFFSET_BITS=64)
    # Android 15+ devices may use 16 KB memory pages.
    target_link_options(sevenzip_android PRIVATE -Wl,-z,max-page-size=16384)
    target_link_libraries(sevenzip_android PRIVATE log)
else ()
    # Desktop build used only for testing the engine (no JNI).
    find_package(Threads REQUIRED)
    add_executable(engine_test tests/engine_test.cpp $<TARGET_OBJECTS:z7obj>)
    target_link_libraries(engine_test PRIVATE Threads::Threads ${CMAKE_DL_LIBS})
endif ()
