cmake_minimum_required(VERSION 3.15)
project(SuperResolutionNativeNSS)
message(STATUS "生成NSS模块")

if (NOT (ON_WINDOWS))
    message(FATAL_ERROR "${LIB_PLATFORM} 平台不支持NSS")
endif()

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if (CMAKE_SYSTEM_NAME MATCHES "Windows")
    add_definitions(-DON_WIN64)
    add_definitions(-D_DISABLE_CONSTEXPR_MUTEX_CONSTRUCTOR)
else ()
    message(FATAL_ERROR "不支持的平台: ${CMAKE_SYSTEM_NAME}")
endif()

if (MSVC)
    add_compile_options(/utf-8 /ZI)
endif()

find_package(Vulkan REQUIRED)

# ============================================================
# Arm NSS SDK (Neural Graphics SDK)
#
# 方案A: 从根 CMakeLists.txt 构建整个 SDK (推荐)
#   neural-graphics-sdk-for-game-engines 的根 CMakeLists.txt 会解析
#   FFX_API_BACKEND=vk_windows_x64，设置 FFX_PLATFORM_NAME=windows_x64，
#   然后通过 ffx-api → sdk 链路构建 ffx_nss_windows_x64 和 ffx_backend_vk_windows_x64。
#
# 方案B: 使用预编译库
#   将 ffx_nss_windows_x64.lib 和 ffx_backend_vk_windows_x64.lib 放到
#   SRNativeNSS/libraries/win64/ 下
# ============================================================

set(NSS_SDK_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../neural-graphics-sdk-for-game-engines")
set(NSS_SDK_LIB_DIR "${CMAKE_CURRENT_SOURCE_DIR}/libraries/${LIB_PLATFORM}")

option(SR_NSS_BUILD_SDK "从源码编译 Arm NSS SDK" ON)

if(SR_NSS_BUILD_SDK)
    # --- 检查子模块是否已初始化 ---
    if(NOT EXISTS "${NSS_SDK_ROOT}/CMakeLists.txt")
        message(FATAL_ERROR
            "Arm NSS SDK 未找到！路径: ${NSS_SDK_ROOT}\n"
            "请确认 neural-graphics-sdk-for-game-engines 仓库已克隆到正确位置\n"
            "或使用 -DSR_NSS_BUILD_SDK=OFF 并手动放置预编译库到\n"
            "  ${NSS_SDK_LIB_DIR}\n")
    endif()

    message(STATUS "[NSS] 从源码构建 Arm NSS SDK ...")

    # NSS SDK 顶层 CMakeLists.txt 需要 FFX_API_BACKEND
    set(FFX_API_BACKEND "vk_windows_x64" CACHE STRING "" FORCE)

    # 临时清除继承的 C++ 标准，避免 SDK 内部编译问题
    set(_SR_PARENT_CXX_STANDARD          "${CMAKE_CXX_STANDARD}")
    set(_SR_PARENT_CXX_STANDARD_REQUIRED "${CMAKE_CXX_STANDARD_REQUIRED}")
    set(CMAKE_POSITION_INDEPENDENT_CODE ON)
    unset(CMAKE_CXX_STANDARD)
    unset(CMAKE_CXX_STANDARD_REQUIRED)

    # 构建 NSS SDK (根 CMakeLists.txt 会自动设置 FFX_NSS=ON)
    add_subdirectory("${NSS_SDK_ROOT}" "${CMAKE_BINARY_DIR}/nss_sdk" EXCLUDE_FROM_ALL)

    # 恢复 C++ 标准
    set(CMAKE_CXX_STANDARD          "${_SR_PARENT_CXX_STANDARD}")
    set(CMAKE_CXX_STANDARD_REQUIRED "${_SR_PARENT_CXX_STANDARD_REQUIRED}")

    # SDK 产生的 target 名称: ffx_nss_windows_x64, ffx_backend_vk_windows_x64
    set(NSS_LINK_BACKEND ffx_backend_vk_windows_x64)
    set(NSS_LINK_NSS     ffx_nss_windows_x64)

    # 将 SDK 的静态库输出重定向到 libraries/${LIB_PLATFORM}/
    foreach(_tgt IN LISTS NSS_LINK_BACKEND NSS_LINK_NSS)
        if(TARGET ${_tgt})
            set_target_properties(${_tgt} PROPERTIES
                ARCHIVE_OUTPUT_DIRECTORY              "${NSS_SDK_LIB_DIR}"
                ARCHIVE_OUTPUT_DIRECTORY_DEBUG         "${NSS_SDK_LIB_DIR}"
                ARCHIVE_OUTPUT_DIRECTORY_RELEASE       "${NSS_SDK_LIB_DIR}"
                ARCHIVE_OUTPUT_DIRECTORY_RELWITHDEBINFO "${NSS_SDK_LIB_DIR}"
            )
        endif()
    endforeach()
else()
    # --- 使用预编译 SDK 库 ---
    message(STATUS "[NSS] 使用预编译 SDK 库: ${NSS_SDK_LIB_DIR}")
    if(NOT EXISTS "${NSS_SDK_LIB_DIR}")
        message(FATAL_ERROR
            "预编译 SDK 库目录不存在: ${NSS_SDK_LIB_DIR}\n"
            "请先构建 SDK 或使用 -DSR_NSS_BUILD_SDK=ON 从源码构建")
    endif()
    link_directories("${NSS_SDK_LIB_DIR}")

    if(CMAKE_BUILD_TYPE STREQUAL "Debug")
        set(NSS_LINK_BACKEND ffx_backend_vk_windows_x64)
        set(NSS_LINK_NSS     ffx_nss_windows_x64)
        set(NSS_LINK_FI      ffx_frameinterpolation_windows_x64)
        set(NSS_LINK_OF      ffx_opticalflow_windows_x64)
        set(NSS_LINK_SPIRV   SPIRV-Tools SPIRV-Tools-opt SPIRV-Tools-link)
    else()
        set(NSS_LINK_BACKEND ffx_backend_vk_windows_x64)
        set(NSS_LINK_NSS     ffx_nss_windows_x64)
        set(NSS_LINK_FI      ffx_frameinterpolation_windows_x64)
        set(NSS_LINK_OF      ffx_opticalflow_windows_x64)
        set(NSS_LINK_SPIRV   SPIRV-Tools SPIRV-Tools-opt SPIRV-Tools-link)
    endif()
endif()

# ============================================================
# 构建 SRNativeNSS 模块
# ============================================================

set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")

link_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}/../SRNativeMain/libraries/${LIB_PLATFORM}
)

include_directories(BEFORE ${Vulkan_INCLUDE_DIRS})

include_directories(
    # Vulkan 头文件必须在最前: third_party/vulkan (旧版 290) 不含 VK_ARM_tensors/VK_ARM_data_graph 定义,
    # vk_wrapper.h 编译需要新版头文件 (VulkanSDK 1.4.350) 中的 ARM PFN 类型。
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${CMAKE_CURRENT_SOURCE_DIR}/../SRNativeMain/include
    # NSS SDK 公共头文件
    ${NSS_SDK_ROOT}/sdk/include
    ${CMAKE_CURRENT_SOURCE_DIR}/src
)

aux_source_directory(${CMAKE_CURRENT_SOURCE_DIR}/src ALL_SRC)
add_library(SR_NSS_LIB SHARED ${ALL_SRC})

target_compile_definitions(SR_NSS_LIB PRIVATE
    ON_WIN64
    _DISABLE_CONSTEXPR_MUTEX_CONSTRUCTOR
    SRLIB_VERSION="${SRLIB_VERSION}"
)

if(CMAKE_SYSTEM_NAME MATCHES "Windows")
    set_target_properties(SR_NSS_LIB PROPERTIES PREFIX "" OUTPUT_NAME "libSuperResolutionNSS+${LIB_PLATFORM}+${SR_BUILD_TYPE}")
endif()

target_link_libraries(SR_NSS_LIB
    SR_MAIN_LIB
    -Wl,--start-group
    ${NSS_LINK_BACKEND}
    ${NSS_LINK_NSS}
    ${NSS_LINK_FI}
    ${NSS_LINK_OF}
    ${NSS_LINK_SPIRV}
    -Wl,--end-group
    ${Vulkan_LIBRARIES}
    -static
)
