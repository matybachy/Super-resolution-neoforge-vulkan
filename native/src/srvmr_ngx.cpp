// JNI bridge between Super Resolution VMR and NVIDIA NGX (DLSS Super Resolution) on Vulkan.
//
// Everything Vulkan is owned by VulkanMod Reforged: the instance, the device, the command buffer of the frame and the
// images. This library only hands their handles to NGX. It keeps no Vulkan state of its own.
//
// Built against the NVIDIA DLSS SDK, which is not part of this repository; see native/README.md.

#include <jni.h>

#include <cstdint>
#include <string>
#include <vector>

#include <vulkan/vulkan.h>

#include "nvsdk_ngx_vk.h"
#include "nvsdk_ngx_helpers.h"
#include "nvsdk_ngx_helpers_vk.h"

namespace {

constexpr const char *kEngineVersion = "1.21.1";

NVSDK_NGX_Result g_lastResult = NVSDK_NGX_Result_Success;
NVSDK_NGX_Parameter *g_parameters = nullptr;
bool g_initialized = false;

// What NGX keeps pointers to: the project id, the data folder, the folder nvngx_dlss.dll is in.
std::string g_projectId;
std::wstring g_dataPath;
std::wstring g_featurePath;
const wchar_t *g_featurePaths[1] = {nullptr};
NVSDK_NGX_FeatureCommonInfo g_featureInfo = {};

std::string utf8(JNIEnv *env, jstring value) {
    if (!value) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string out(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return out;
}

std::wstring wide(JNIEnv *env, jstring value) {
    if (!value) return {};
    const jchar *chars = env->GetStringChars(value, nullptr);
    jsize length = env->GetStringLength(value);
    std::wstring out(reinterpret_cast<const wchar_t *>(chars), static_cast<size_t>(length));
    env->ReleaseStringChars(value, chars);
    return out;
}

void remember(JNIEnv *env, jstring projectId, jstring dataPath, jstring featurePath) {
    g_projectId = utf8(env, projectId);
    g_dataPath = wide(env, dataPath);
    g_featurePath = wide(env, featurePath);
    g_featurePaths[0] = g_featurePath.c_str();
    g_featureInfo = {};
    g_featureInfo.PathListInfo.Path = g_featurePaths;
    g_featureInfo.PathListInfo.Length = 1;
    g_featureInfo.LoggingInfo.LoggingCallback = nullptr;
    g_featureInfo.LoggingInfo.MinimumLoggingLevel = NVSDK_NGX_LOGGING_LEVEL_ON;
    g_featureInfo.LoggingInfo.DisableOtherLoggingSinks = false;
}

NVSDK_NGX_FeatureDiscoveryInfo discovery() {
    NVSDK_NGX_FeatureDiscoveryInfo info = {};
    info.SDKVersion = NVSDK_NGX_Version_API;
    info.FeatureID = NVSDK_NGX_Feature_SuperSampling;
    info.Identifier.IdentifierType = NVSDK_NGX_Application_Identifier_Type_Project_Id;
    info.Identifier.v.ProjectDesc.ProjectId = g_projectId.c_str();
    info.Identifier.v.ProjectDesc.EngineType = NVSDK_NGX_ENGINE_TYPE_CUSTOM;
    info.Identifier.v.ProjectDesc.EngineVersion = kEngineVersion;
    info.ApplicationDataPath = g_dataPath.c_str();
    info.FeatureInfo = &g_featureInfo;
    return info;
}

NVSDK_NGX_Resource_VK imageResource(const jlong *handles, const jint *info, int index, bool readWrite) {
    VkImageSubresourceRange range = {};
    range.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    range.baseMipLevel = 0;
    range.levelCount = 1;
    range.baseArrayLayer = 0;
    range.layerCount = 1;
    return NVSDK_NGX_Create_ImageView_Resource_VK(
        reinterpret_cast<VkImageView>(handles[index * 2 + 1]),
        reinterpret_cast<VkImage>(handles[index * 2]),
        range,
        static_cast<VkFormat>(info[index * 3]),
        static_cast<unsigned int>(info[index * 3 + 1]),
        static_cast<unsigned int>(info[index * 3 + 2]),
        readWrite);
}

}  // namespace

extern "C" {

static jobjectArray names(JNIEnv *env, uint32_t count, const VkExtensionProperties *properties) {
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray(static_cast<jsize>(properties ? count : 0), stringClass, nullptr);
    for (uint32_t i = 0; properties && i < count; i++) {
        jstring name = env->NewStringUTF(properties[i].extensionName);
        env->SetObjectArrayElement(out, static_cast<jsize>(i), name);
        env->DeleteLocalRef(name);
    }
    return out;
}

// The instance extensions DLSS needs, asked before VulkanMod Reforged creates its instance. Null where NGX cannot say.
JNIEXPORT jobjectArray JNICALL Java_dev_matybachy_srvmr_ngx_NgxBridge_instanceExtensions(
    JNIEnv *env, jclass, jstring projectId, jstring dataPath, jstring featurePath) {
    remember(env, projectId, dataPath, featurePath);
    NVSDK_NGX_FeatureDiscoveryInfo info = discovery();
    uint32_t count = 0;
    VkExtensionProperties *properties = nullptr;
    g_lastResult = NVSDK_NGX_VULKAN_GetFeatureInstanceExtensionRequirements(&info, &count, &properties);
    if (NVSDK_NGX_FAILED(g_lastResult)) return nullptr;
    return names(env, count, properties);
}

// The device extensions DLSS needs on this GPU, asked before VulkanMod Reforged creates its device. Null where NGX
// cannot say; the caller then adds nothing.
JNIEXPORT jobjectArray JNICALL Java_dev_matybachy_srvmr_ngx_NgxBridge_deviceExtensions(
    JNIEnv *env, jclass, jstring projectId, jstring dataPath, jstring featurePath, jlong instance, jlong physicalDevice) {
    remember(env, projectId, dataPath, featurePath);
    NVSDK_NGX_FeatureDiscoveryInfo info = discovery();
    uint32_t count = 0;
    VkExtensionProperties *properties = nullptr;
    g_lastResult = NVSDK_NGX_VULKAN_GetFeatureDeviceExtensionRequirements(
        reinterpret_cast<VkInstance>(instance), reinterpret_cast<VkPhysicalDevice>(physicalDevice), &info, &count,
        &properties);
    if (NVSDK_NGX_FAILED(g_lastResult)) return nullptr;
    return names(env, count, properties);
}

JNIEXPORT jint JNICALL Java_dev_matybachy_srvmr_ngx_NgxBridge_init(
    JNIEnv *env, jclass, jstring projectId, jstring dataPath, jstring featurePath, jlong instance,
    jlong physicalDevice, jlong device, jlong getInstanceProcAddr, jlong getDeviceProcAddr) {
    remember(env, projectId, dataPath, featurePath);
    g_lastResult = NVSDK_NGX_VULKAN_Init_with_ProjectID(
        g_projectId.c_str(), NVSDK_NGX_ENGINE_TYPE_CUSTOM, kEngineVersion, g_dataPath.c_str(),
        reinterpret_cast<VkInstance>(instance), reinterpret_cast<VkPhysicalDevice>(physicalDevice),
        reinterpret_cast<VkDevice>(device), reinterpret_cast<PFN_vkGetInstanceProcAddr>(getInstanceProcAddr),
        reinterpret_cast<PFN_vkGetDeviceProcAddr>(getDeviceProcAddr), &g_featureInfo, NVSDK_NGX_Version_API);
    if (NVSDK_NGX_FAILED(g_lastResult)) return static_cast<jint>(g_lastResult);
    g_initialized = true;
    g_lastResult = NVSDK_NGX_VULKAN_GetCapabilityParameters(&g_parameters);
    return static_cast<jint>(g_lastResult);
}

// 1 where DLSS Super Resolution can run here, 0 where it cannot, negative where NGX could not be asked. The second
// value is whether NGX says the driver is too old.
JNIEXPORT jintArray JNICALL Java_dev_matybachy_srvmr_ngx_NgxBridge_availability(JNIEnv *env, jclass) {
    jint values[2] = {-1, 0};
    if (g_parameters) {
        int available = 0;
        int needsDriver = 0;
        if (NVSDK_NGX_SUCCEED(NVSDK_NGX_Parameter_GetI(g_parameters, NVSDK_NGX_Parameter_SuperSampling_Available,
                                                       &available))) {
            values[0] = available ? 1 : 0;
        }
        NVSDK_NGX_Parameter_GetI(g_parameters, NVSDK_NGX_Parameter_SuperSampling_NeedsUpdatedDriver, &needsDriver);
        values[1] = needsDriver;
    }
    jintArray out = env->NewIntArray(2);
    env->SetIntArrayRegion(out, 0, 2, values);
    return out;
}

// The render sizes DLSS accepts for an output size and quality: optimal, largest and smallest, as six ints. Null
// where DLSS cannot say.
JNIEXPORT jintArray JNICALL Java_dev_matybachy_srvmr_ngx_NgxBridge_renderSizes(
    JNIEnv *env, jclass, jint outputWidth, jint outputHeight, jint quality) {
    if (!g_parameters) return nullptr;
    unsigned int values[6] = {};
    float sharpness = 0.0f;
    g_lastResult = NGX_DLSS_GET_OPTIMAL_SETTINGS(
        g_parameters, static_cast<unsigned int>(outputWidth), static_cast<unsigned int>(outputHeight),
        static_cast<NVSDK_NGX_PerfQuality_Value>(quality), &values[0], &values[1], &values[2], &values[3], &values[4],
        &values[5], &sharpness);
    if (NVSDK_NGX_FAILED(g_lastResult)) return nullptr;
    jint ints[6];
    for (int i = 0; i < 6; i++) ints[i] = static_cast<jint>(values[i]);
    jintArray out = env->NewIntArray(6);
    env->SetIntArrayRegion(out, 0, 6, ints);
    return out;
}

// Records the creation of a DLSS feature into the command buffer. The handle, or 0; lastResult says why.
JNIEXPORT jlong JNICALL Java_dev_matybachy_srvmr_ngx_NgxBridge_create(
    JNIEnv *, jclass, jlong commandBuffer, jint renderWidth, jint renderHeight, jint outputWidth, jint outputHeight,
    jint quality, jint flags) {
    if (!g_parameters) return 0;
    NVSDK_NGX_DLSS_Create_Params create = {};
    create.Feature.InWidth = static_cast<unsigned int>(renderWidth);
    create.Feature.InHeight = static_cast<unsigned int>(renderHeight);
    create.Feature.InTargetWidth = static_cast<unsigned int>(outputWidth);
    create.Feature.InTargetHeight = static_cast<unsigned int>(outputHeight);
    create.Feature.InPerfQualityValue = static_cast<NVSDK_NGX_PerfQuality_Value>(quality);
    create.InFeatureCreateFlags = flags;
    create.InEnableOutputSubrects = false;
    NVSDK_NGX_Handle *handle = nullptr;
    g_lastResult = NGX_VULKAN_CREATE_DLSS_EXT(reinterpret_cast<VkCommandBuffer>(commandBuffer), 1, 1, &handle,
                                              g_parameters, &create);
    if (NVSDK_NGX_FAILED(g_lastResult)) return 0;
    return reinterpret_cast<jlong>(handle);
}

// Records one DLSS evaluation. images: image and view of colour, depth, motion vectors and output, in that order;
// info: format, width and height of each.
JNIEXPORT jint JNICALL Java_dev_matybachy_srvmr_ngx_NgxBridge_evaluate(
    JNIEnv *env, jclass, jlong commandBuffer, jlong feature, jlongArray images, jintArray info, jfloat jitterX,
    jfloat jitterY, jint reset, jfloat frameMillis, jint renderWidth, jint renderHeight) {
    if (!g_parameters || !feature) return static_cast<jint>(NVSDK_NGX_Result_FAIL_InvalidParameter);
    if (env->GetArrayLength(images) < 8 || env->GetArrayLength(info) < 12) {
        return static_cast<jint>(NVSDK_NGX_Result_FAIL_InvalidParameter);
    }
    jlong handles[8];
    jint formats[12];
    env->GetLongArrayRegion(images, 0, 8, handles);
    env->GetIntArrayRegion(info, 0, 12, formats);

    NVSDK_NGX_Resource_VK color = imageResource(handles, formats, 0, false);
    NVSDK_NGX_Resource_VK depth = imageResource(handles, formats, 1, false);
    NVSDK_NGX_Resource_VK motion = imageResource(handles, formats, 2, false);
    NVSDK_NGX_Resource_VK output = imageResource(handles, formats, 3, true);

    NVSDK_NGX_VK_DLSS_Eval_Params eval = {};
    eval.Feature.pInColor = &color;
    eval.Feature.pInOutput = &output;
    eval.Feature.InSharpness = 0.0f;
    eval.pInDepth = &depth;
    eval.pInMotionVectors = &motion;
    eval.InJitterOffsetX = jitterX;
    eval.InJitterOffsetY = jitterY;
    eval.InRenderSubrectDimensions.Width = static_cast<unsigned int>(renderWidth);
    eval.InRenderSubrectDimensions.Height = static_cast<unsigned int>(renderHeight);
    eval.InReset = reset;
    eval.InMVScaleX = 1.0f;
    eval.InMVScaleY = 1.0f;
    eval.InPreExposure = 1.0f;
    eval.InExposureScale = 1.0f;
    eval.InFrameTimeDeltaInMsec = frameMillis;
    g_lastResult = NGX_VULKAN_EVALUATE_DLSS_EXT(reinterpret_cast<VkCommandBuffer>(commandBuffer),
                                                reinterpret_cast<NVSDK_NGX_Handle *>(feature), g_parameters, &eval);
    return static_cast<jint>(g_lastResult);
}

JNIEXPORT void JNICALL Java_dev_matybachy_srvmr_ngx_NgxBridge_release(JNIEnv *, jclass, jlong feature) {
    if (feature) g_lastResult = NVSDK_NGX_VULKAN_ReleaseFeature(reinterpret_cast<NVSDK_NGX_Handle *>(feature));
}

JNIEXPORT void JNICALL Java_dev_matybachy_srvmr_ngx_NgxBridge_shutdown(JNIEnv *, jclass, jlong device) {
    if (g_parameters) {
        NVSDK_NGX_VULKAN_DestroyParameters(g_parameters);
        g_parameters = nullptr;
    }
    if (g_initialized) {
        g_lastResult = NVSDK_NGX_VULKAN_Shutdown1(reinterpret_cast<VkDevice>(device));
        g_initialized = false;
    }
}

JNIEXPORT jint JNICALL Java_dev_matybachy_srvmr_ngx_NgxBridge_lastResult(JNIEnv *, jclass) {
    return static_cast<jint>(g_lastResult);
}

}  // extern "C"
