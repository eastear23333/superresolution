cmake_minimum_required(VERSION 3.15)
project(SuperResolutionNativeNSS)
message(STATUS "生成NSS模块")

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# NSS 走 Vulkan + DP4A，Windows / Linux 均可
if (CMAKE_SYSTEM_NAME MATCHES "Windows")
    add_definitions(-DON_WIN64)
    add_definitions(-D_DISABLE_CONSTEXPR_MUTEX_CONSTRUCTOR)
elseif (CMAKE_SYSTEM_NAME MATCHES "Linux")
    add_definitions(-DON_LINUX64 -DFFX_GCC)
else ()
    message(FATAL_ERROR "${LIB_PLATFORM} 平台暂不支持NSS")
endif ()
if (MSVC)
    add_compile_options(/utf-8 /ZI)
endif ()

find_package(Vulkan REQUIRED)
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")

include_directories(
        ${PROJECT_SOURCE_DIR}/include
        ${PROJECT_SOURCE_DIR}/../SRNativeMain/include
        ${PROJECT_SOURCE_DIR}/src
)

# DP4A 推理后端：运行时由 nss.cpp 动态加载（LoadLibrary），不做链接期依赖。
# 只需要头文件；nss_dp4a.dll 由 Java 侧通过 extraParams("NSS_DP4A_DLL_PATH") 传入路径。
# NSS_DP4A_DIR 可用 -DNSS_DP4A_DIR=<path> 覆盖
if (NOT DEFINED NSS_DP4A_DIR)
    set(NSS_DP4A_DIR "${PROJECT_SOURCE_DIR}/third_party/nss_dp4a")
endif ()
if (NOT EXISTS "${NSS_DP4A_DIR}/include/nss_dp4a.h")
    message(FATAL_ERROR
        "未找到 NSS DP4A 后端头文件: ${NSS_DP4A_DIR}/include/nss_dp4a.h\n"
        "请把 nss-dll 的 include/ 复制到 SRNativeNSS/third_party/nss_dp4a/include/，\n"
        "或用 -DNSS_DP4A_DIR=<path> 指定。"
    )
endif ()
include_directories(${NSS_DP4A_DIR}/include)

aux_source_directory(${PROJECT_SOURCE_DIR}/src ALL_SRC)
add_library(SR_NSS_LIB SHARED ${ALL_SRC})
set_target_properties(SR_NSS_LIB PROPERTIES OUTPUT_NAME "libSuperResolutionNSS+${LIB_PLATFORM}+${SR_BUILD_TYPE}")

target_link_libraries(SR_NSS_LIB
        SR_MAIN_LIB
        ${Vulkan_LIBRARIES}
)
