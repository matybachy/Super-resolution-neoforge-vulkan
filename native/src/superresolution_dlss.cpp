#include <jni.h>
#include <vulkan/vulkan.h>

#include "nvsdk_ngx_helpers_vk.h"

#include <cstdlib>
#include <cwchar>
#include <string>

namespace {
NVSDK_NGX_Parameter *g_parameters = nullptr;
NVSDK_NGX_Handle *g_feature = nullptr;
unsigned int g_width = 0;
unsigned int g_height = 0;

jlong field(JNIEnv *env, jobject object, const char *name) {
    jclass type = env->GetObjectClass(object);
    jfieldID id = env->GetFieldID(type, name, "J");
    jlong value = id ? env->GetLongField(object, id) : 0;
    env->DeleteLocalRef(type);
    return value;
}

jint intField(JNIEnv *env, jobject object, const char *name) {
    jclass type = env->GetObjectClass(object);
    jfieldID id = env->GetFieldID(type, name, "I");
    jint value = id ? env->GetIntField(object, id) : 0;
    env->DeleteLocalRef(type);
    return value;
}

bool boolField(JNIEnv *env, jobject object, const char *name) {
    jclass type = env->GetObjectClass(object);
    jfieldID id = env->GetFieldID(type, name, "Z");
    bool value = id && env->GetBooleanField(object, id);
    env->DeleteLocalRef(type);
    return value;
}

NVSDK_NGX_Resource_VK resource(JNIEnv *env, jobject image, bool readWrite) {
    VkImageSubresourceRange range{};
    range.aspectMask = static_cast<VkImageAspectFlags>(intField(env, image, "aspectMask"));
    range.levelCount = 1;
    range.layerCount = 1;
    return NVSDK_NGX_Create_ImageView_Resource_VK(
            reinterpret_cast<VkImageView>(field(env, image, "imageView")),
            reinterpret_cast<VkImage>(field(env, image, "image")),
            range,
            static_cast<VkFormat>(intField(env, image, "format")),
            static_cast<unsigned int>(intField(env, image, "width")),
            static_cast<unsigned int>(intField(env, image, "height")),
            readWrite);
}

void release() {
    if (g_feature) {
        NVSDK_NGX_VULKAN_ReleaseFeature(g_feature);
        g_feature = nullptr;
    }
    if (g_parameters) {
        NVSDK_NGX_VULKAN_DestroyParameters(g_parameters);
        g_parameters = nullptr;
    }
    g_width = 0;
    g_height = 0;
}
}

extern "C" {
JNIEXPORT jint JNICALL Java_dev_matybachy_superresolution_DlssNativeBridge_initialize(
        JNIEnv *, jclass, jlong instance, jlong physicalDevice, jlong device,
        jlong getInstanceProcAddr, jlong getDeviceProcAddr) {
    const char *projectId = std::getenv("SUPERRESOLUTION_NGX_PROJECT_ID");
    if (!projectId || !*projectId) return -1;
    wchar_t appPath[1024]{};
    std::mbstowcs(appPath, ".", sizeof(appPath) / sizeof(wchar_t) - 1);
    NVSDK_NGX_Result result = NVSDK_NGX_VULKAN_Init_with_ProjectID(
            projectId, NVSDK_NGX_ENGINE_TYPE_CUSTOM,
            "Super-resolution-neoforge-vulkan", appPath,
            reinterpret_cast<VkInstance>(instance),
            reinterpret_cast<VkPhysicalDevice>(physicalDevice),
            reinterpret_cast<VkDevice>(device),
            reinterpret_cast<PFN_vkGetInstanceProcAddr>(getInstanceProcAddr),
            reinterpret_cast<PFN_vkGetDeviceProcAddr>(getDeviceProcAddr));
    if (NVSDK_NGX_FAILED(result)) return static_cast<jint>(result);
    return static_cast<jint>(NVSDK_NGX_VULKAN_AllocateParameters(&g_parameters));
}

JNIEXPORT jint JNICALL Java_dev_matybachy_superresolution_DlssNativeBridge_evaluate(
        JNIEnv *env, jclass, jlong commandBuffer, jobject color, jobject depth,
        jobject motionVectors, jobject output, jobject frame) {
    if (!g_parameters || !color || !depth || !motionVectors || !output || !frame) return -1;
    unsigned int width = static_cast<unsigned int>(intField(env, color, "width"));
    unsigned int height = static_cast<unsigned int>(intField(env, color, "height"));
    if (!g_feature || width != g_width || height != g_height) {
        release();
        if (NVSDK_NGX_FAILED(NVSDK_NGX_VULKAN_AllocateParameters(&g_parameters))) return -1;
        g_width = width;
        g_height = height;
        NVSDK_NGX_DLSS_Create_Params create{};
        create.Feature.InWidth = width;
        create.Feature.InHeight = height;
        create.Feature.InTargetWidth = static_cast<unsigned int>(intField(env, output, "width"));
        create.Feature.InTargetHeight = static_cast<unsigned int>(intField(env, output, "height"));
        create.InFeatureCreateFlags = NVSDK_NGX_DLSS_Feature_Flags_MVLowRes
                                      | NVSDK_NGX_DLSS_Feature_Flags_MVJittered;
        if (NVSDK_NGX_FAILED(NGX_VULKAN_CREATE_DLSS_EXT(
                reinterpret_cast<VkCommandBuffer>(commandBuffer), 1, 1,
                &g_feature, g_parameters, &create))) return -1;
    }
    NVSDK_NGX_VK_DLSS_Eval_Params eval{};
    auto colorResource = resource(env, color, true);
    auto depthResource = resource(env, depth, false);
    auto motionResource = resource(env, motionVectors, true);
    auto outputResource = resource(env, output, true);
    eval.Feature.pInColor = &colorResource;
    eval.Feature.pInOutput = &outputResource;
    eval.pInDepth = &depthResource;
    eval.pInMotionVectors = &motionResource;
    eval.Feature.InSharpness = 0.0F;
    eval.InJitterOffsetX = 0.0F;
    eval.InJitterOffsetY = 0.0F;
    eval.InRenderSubrectDimensions = {width, height};
    eval.InReset = boolField(env, frame, "resetHistory") ? 1 : 0;
    eval.InMVScaleX = 1.0F;
    eval.InMVScaleY = 1.0F;
    eval.InPreExposure = 1.0F;
    eval.InExposureScale = 1.0F;
    eval.InFrameTimeDeltaInMsec = 1000.0F * 0.016F;
    return static_cast<jint>(NGX_VULKAN_EVALUATE_DLSS_EXT(
            reinterpret_cast<VkCommandBuffer>(commandBuffer), g_feature, g_parameters, &eval));
}

JNIEXPORT void JNICALL Java_dev_matybachy_superresolution_DlssNativeBridge_reset(JNIEnv *, jclass) {
}

JNIEXPORT void JNICALL Java_dev_matybachy_superresolution_DlssNativeBridge_shutdown(JNIEnv *, jclass) {
    release();
    NVSDK_NGX_VULKAN_Shutdown1(VK_NULL_HANDLE);
}
}
