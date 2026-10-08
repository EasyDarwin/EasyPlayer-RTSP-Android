cmake_minimum_required(VERSION 3.18)
project(EasyPlayerFFmpeg LANGUAGES CXX)
if(NOT FFMPEG_ROOT)
    message(FATAL_ERROR "Use scripts/build-native.py to build FFmpeg first")
endif()
add_library(EasyPlayerFFmpeg SHARED easyplayer_jni.cpp)
target_compile_features(EasyPlayerFFmpeg PRIVATE cxx_std_17)
target_compile_options(EasyPlayerFFmpeg PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
target_include_directories(EasyPlayerFFmpeg PRIVATE "${FFMPEG_ROOT}/include")
target_link_options(EasyPlayerFFmpeg PRIVATE
    -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384
    -Wl,-z,relro -Wl,-z,now -Wl,--exclude-libs,ALL -Wl,--no-undefined)
target_link_libraries(EasyPlayerFFmpeg PRIVATE -Wl,--start-group
    "${FFMPEG_ROOT}/lib/libavformat.a" "${FFMPEG_ROOT}/lib/libavcodec.a"
    "${FFMPEG_ROOT}/lib/libswscale.a" "${FFMPEG_ROOT}/lib/libswresample.a"
    "${FFMPEG_ROOT}/lib/libavutil.a" -Wl,--end-group android log z m)
