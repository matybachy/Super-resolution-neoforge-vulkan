cmake_minimum_required(VERSION 3.20)
project(superresolution_dlss LANGUAGES CXX)

set(DLSS_SDK_DIR "C:/dev/DLSS" CACHE PATH "External NVIDIA DLSS SDK checkout")
find_package(JNI REQUIRED)
find_package(Vulkan REQUIRED)

if(WIN32)
    set(DLSS_LIB_DIR "${DLSS_SDK_DIR}/lib/Windows_x86_64/x64")
    set(DLSS_LIB "nvsdk_ngx_s.lib")
else()
    set(DLSS_LIB_DIR "${DLSS_SDK_DIR}/lib/Linux_x86_64")
    set(DLSS_LIB "libnvsdk_ngx.a")
endif()

add_library(superresolution_dlss SHARED src/superresolution_dlss.cpp)
target_compile_features(superresolution_dlss PRIVATE cxx_std_20)
target_include_directories(superresolution_dlss PRIVATE
        "${DLSS_SDK_DIR}/include"
        ${JNI_INCLUDE_DIRS}
        ${Vulkan_INCLUDE_DIRS})
target_link_directories(superresolution_dlss PRIVATE "${DLSS_LIB_DIR}")
target_link_libraries(superresolution_dlss PRIVATE ${DLSS_LIB} Vulkan::Vulkan)

find_program(GLSLANG_VALIDATOR glslangValidator)
if(NOT GLSLANG_VALIDATOR)
    message(FATAL_ERROR "glslangValidator is required to compile native/shaders/camera_motion.comp")
endif()
set(CAMERA_MOTION_SPV "${CMAKE_CURRENT_BINARY_DIR}/camera_motion.comp.spv")
find_package(Python3 COMPONENTS Interpreter REQUIRED)
set(CAMERA_MOTION_HEADER "${CMAKE_CURRENT_BINARY_DIR}/camera_motion_spv.h")
add_custom_command(
        OUTPUT "${CAMERA_MOTION_SPV}"
        COMMAND "${GLSLANG_VALIDATOR}" -V
        "${CMAKE_CURRENT_SOURCE_DIR}/shaders/camera_motion.comp"
        -o "${CAMERA_MOTION_SPV}"
        DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/shaders/camera_motion.comp")
add_custom_command(
        OUTPUT "${CAMERA_MOTION_HEADER}"
        COMMAND "${Python3_EXECUTABLE}" "${CMAKE_CURRENT_SOURCE_DIR}/embed_spirv.py"
        "${CAMERA_MOTION_SPV}" "${CAMERA_MOTION_HEADER}"
        DEPENDS "${CAMERA_MOTION_SPV}" "${CMAKE_CURRENT_SOURCE_DIR}/embed_spirv.py")
add_custom_target(superresolution_dlss_shaders DEPENDS "${CAMERA_MOTION_HEADER}")
add_dependencies(superresolution_dlss superresolution_dlss_shaders)
target_include_directories(superresolution_dlss PRIVATE "${CMAKE_CURRENT_BINARY_DIR}")

if(WIN32)
    set_target_properties(superresolution_dlss PROPERTIES OUTPUT_NAME "superresolution_dlss")
endif()
