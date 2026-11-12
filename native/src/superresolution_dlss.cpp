#include <jni.h>
#include <vulkan/vulkan.h>

#include "nvsdk_ngx_helpers_vk.h"
#include "camera_motion_spv.h"

#include <cstdlib>
#include <cwchar>
#include <string>

namespace {
NVSDK_NGX_Parameter *g_parameters = nullptr;
NVSDK_NGX_Handle *g_feature = nullptr;
VkDevice g_device = VK_NULL_HANDLE;
VkPipeline g_motionPipeline = VK_NULL_HANDLE;
VkPipelineLayout g_motionPipelineLayout = VK_NULL_HANDLE;
VkDescriptorSetLayout g_motionSetLayout = VK_NULL_HANDLE;
VkDescriptorPool g_motionDescriptorPool = VK_NULL_HANDLE;
VkDescriptorSet g_motionDescriptorSet = VK_NULL_HANDLE;
VkSampler g_motionSampler = VK_NULL_HANDLE;
bool g_motionInitialized = false;
VkImage g_motionImage = VK_NULL_HANDLE;
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

bool createMotionPipeline() {
    VkDescriptorSetLayoutBinding bindings[2]{};
    bindings[0].binding = 0;
    bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    bindings[0].descriptorCount = 1;
    bindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings[1].binding = 1;
    bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    bindings[1].descriptorCount = 1;
    bindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    VkDescriptorSetLayoutCreateInfo layoutInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    layoutInfo.bindingCount = 2;
    layoutInfo.pBindings = bindings;
    if (vkCreateDescriptorSetLayout(g_device, &layoutInfo, nullptr, &g_motionSetLayout) != VK_SUCCESS) return false;
    VkPushConstantRange push{VK_SHADER_STAGE_COMPUTE_BIT, 0, 192};
    VkPipelineLayoutCreateInfo pipelineLayout{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    pipelineLayout.setLayoutCount = 1;
    pipelineLayout.pSetLayouts = &g_motionSetLayout;
    pipelineLayout.pushConstantRangeCount = 1;
    pipelineLayout.pPushConstantRanges = &push;
    if (vkCreatePipelineLayout(g_device, &pipelineLayout, nullptr, &g_motionPipelineLayout) != VK_SUCCESS) return false;
    VkShaderModuleCreateInfo moduleInfo{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    moduleInfo.codeSize = camera_motion_comp_spv_len;
    moduleInfo.pCode = reinterpret_cast<const uint32_t *>(camera_motion_comp_spv);
    VkShaderModule module = VK_NULL_HANDLE;
    if (vkCreateShaderModule(g_device, &moduleInfo, nullptr, &module) != VK_SUCCESS) return false;
    VkComputePipelineCreateInfo pipelineInfo{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
    pipelineInfo.stage = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};
    pipelineInfo.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    pipelineInfo.stage.module = module;
    pipelineInfo.stage.pName = "main";
    pipelineInfo.layout = g_motionPipelineLayout;
    VkResult result = vkCreateComputePipelines(g_device, VK_NULL_HANDLE, 1, &pipelineInfo, nullptr, &g_motionPipeline);
    vkDestroyShaderModule(g_device, module, nullptr);
    if (result != VK_SUCCESS) return false;
    VkSamplerCreateInfo sampler{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
    sampler.magFilter = VK_FILTER_NEAREST;
    sampler.minFilter = VK_FILTER_NEAREST;
    sampler.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    sampler.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    sampler.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    sampler.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    sampler.maxLod = 1.0F;
    if (vkCreateSampler(g_device, &sampler, nullptr, &g_motionSampler) != VK_SUCCESS) return false;
    VkDescriptorPoolSize sizes[2]{{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1},
                                  {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1}};
    VkDescriptorPoolCreateInfo pool{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    pool.maxSets = 1;
    pool.poolSizeCount = 2;
    pool.pPoolSizes = sizes;
    if (vkCreateDescriptorPool(g_device, &pool, nullptr, &g_motionDescriptorPool) != VK_SUCCESS) return false;
    VkDescriptorSetAllocateInfo alloc{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    alloc.descriptorPool = g_motionDescriptorPool;
    alloc.descriptorSetCount = 1;
    alloc.pSetLayouts = &g_motionSetLayout;
    return vkAllocateDescriptorSets(g_device, &alloc, &g_motionDescriptorSet) == VK_SUCCESS;
}

void destroyMotionPipeline() {
    if (g_motionSampler) vkDestroySampler(g_device, g_motionSampler, nullptr);
    if (g_motionDescriptorPool) vkDestroyDescriptorPool(g_device, g_motionDescriptorPool, nullptr);
    if (g_motionPipeline) vkDestroyPipeline(g_device, g_motionPipeline, nullptr);
    if (g_motionPipelineLayout) vkDestroyPipelineLayout(g_device, g_motionPipelineLayout, nullptr);
    if (g_motionSetLayout) vkDestroyDescriptorSetLayout(g_device, g_motionSetLayout, nullptr);
    g_motionSampler = VK_NULL_HANDLE;
    g_motionDescriptorPool = VK_NULL_HANDLE;
    g_motionPipeline = VK_NULL_HANDLE;
    g_motionPipelineLayout = VK_NULL_HANDLE;
    g_motionSetLayout = VK_NULL_HANDLE;
    g_motionDescriptorSet = VK_NULL_HANDLE;
    g_motionInitialized = false;
    g_motionImage = VK_NULL_HANDLE;
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
    g_device = reinterpret_cast<VkDevice>(device);
    if (!createMotionPipeline()) {
        NVSDK_NGX_VULKAN_Shutdown1(VK_NULL_HANDLE);
        g_device = VK_NULL_HANDLE;
        return -2;
    }
    return static_cast<jint>(NVSDK_NGX_VULKAN_AllocateParameters(&g_parameters));
}

JNIEXPORT jint JNICALL Java_dev_matybachy_superresolution_DlssNativeBridge_recordMotionVectors(
        JNIEnv *env, jclass, jlong commandBuffer, jobject depth, jobject motion,
        jfloatArray inverseCurrent, jfloatArray previous, jfloatArray current) {
    if (!g_motionPipeline || !depth || !motion || !inverseCurrent || !previous || !current) return -1;
    jsize length = env->GetArrayLength(inverseCurrent);
    if (length != 16 || env->GetArrayLength(previous) != 16 || env->GetArrayLength(current) != 16) return -1;
    float matrices[48];
    env->GetFloatArrayRegion(inverseCurrent, 0, 16, matrices);
    env->GetFloatArrayRegion(previous, 0, 16, matrices + 16);
    env->GetFloatArrayRegion(current, 0, 16, matrices + 32);
    VkImage motionImage = reinterpret_cast<VkImage>(field(env, motion, "image"));
    bool sameMotionImage = g_motionInitialized && g_motionImage == motionImage;
    VkDescriptorImageInfo depthInfo{g_motionSampler, reinterpret_cast<VkImageView>(field(env, depth, "imageView")),
                                    VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL};
    VkDescriptorImageInfo motionInfo{VK_NULL_HANDLE, reinterpret_cast<VkImageView>(field(env, motion, "imageView")),
                                     VK_IMAGE_LAYOUT_GENERAL};
    VkWriteDescriptorSet writes[2]{};
    writes[0] = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr, g_motionDescriptorSet, 0, 0, 1,
                 VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, &depthInfo, nullptr, nullptr};
    writes[1] = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr, g_motionDescriptorSet, 1, 0, 1,
                 VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, &motionInfo, nullptr, nullptr};
    vkUpdateDescriptorSets(g_device, 2, writes, 0, nullptr);
    VkCommandBuffer command = reinterpret_cast<VkCommandBuffer>(commandBuffer);
    VkImageMemoryBarrier barrier{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    barrier.srcAccessMask = sameMotionImage ? VK_ACCESS_SHADER_WRITE_BIT : 0;
    barrier.dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    barrier.oldLayout = sameMotionImage ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_UNDEFINED;
    barrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    barrier.image = motionImage;
    barrier.subresourceRange.aspectMask = static_cast<VkImageAspectFlags>(intField(env, motion, "aspectMask"));
    barrier.subresourceRange.levelCount = 1;
    barrier.subresourceRange.layerCount = 1;
    vkCmdPipelineBarrier(command, sameMotionImage ? VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT : VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1, &barrier);
    vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_COMPUTE, g_motionPipeline);
    vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_COMPUTE, g_motionPipelineLayout, 0, 1,
                            &g_motionDescriptorSet, 0, nullptr);
    vkCmdPushConstants(command, g_motionPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(matrices), matrices);
    vkCmdDispatch(command, (intField(env, motion, "width") + 7) / 8,
                  (intField(env, motion, "height") + 7) / 8, 1);
    g_motionInitialized = true;
    g_motionImage = motionImage;
    return 0;
}

JNIEXPORT jint JNICALL Java_dev_matybachy_superresolution_DlssNativeBridge_evaluate(
        JNIEnv *env, jclass, jlong commandBuffer, jobject color, jobject depth,
        jobject motionVectors, jobject output, jobject frame, jfloat sharpness) {
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
    eval.Feature.InSharpness = sharpness;
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
    destroyMotionPipeline();
    NVSDK_NGX_VULKAN_Shutdown1(VK_NULL_HANDLE);
}
}
