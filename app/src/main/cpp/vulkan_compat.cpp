// Vulkan 1.1 compatibility shim for the ggml-vulkan backend.
//
// ggml-vulkan (llama.cpp b11179) calls vkGetPhysicalDeviceFeatures2() directly
// at three sites (device feature probing for 16-bit storage / cooperative
// matrix support). That is a Vulkan 1.1 entry point: the NDK's libvulkan.so
// stub only exports it for API level 28+, and Android 8.0/8.1 (API 26/27,
// still inside our minSdk) platform loaders do not export it either. A direct
// DT_NEEDED binding would therefore fail at dlopen time and crash the whole
// app on those devices.
//
// This shim provides the symbol locally so the link never depends on the
// platform's 1.1 exports, and forwards to the real driver entry point at
// runtime when it exists (API 28+, which is every Vulkan-capable Adreno
// device). On older loaders the query degrades to "no 1.1 features", which
// makes ggml skip the coopmat kernels instead of failing to load.

#include <cstring>
#include <dlfcn.h>

extern "C" {
#include <vulkan/vulkan.h>
}

typedef void (VKAPI_PTR * PFN_vkGetPhysicalDeviceFeatures2_t)(VkPhysicalDevice, VkPhysicalDeviceFeatures2 *);

// Zero the base struct and any chained feature struct we know the size of, so
// callers read deterministic "unsupported" instead of uninitialized stack.
static void vulkan_compat_zero_features2(VkPhysicalDeviceFeatures2 * pFeatures)
{
    if (pFeatures == nullptr) {
        return;
    }
    memset(pFeatures, 0, sizeof(*pFeatures));

    for (VkBaseOutStructure * hdr = reinterpret_cast<VkBaseOutStructure *>(pFeatures->pNext);
         hdr != nullptr;) {
        VkBaseOutStructure * next = reinterpret_cast<VkBaseOutStructure *>(hdr->pNext);
        switch (hdr->sType) {
        case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES:
            memset(hdr, 0, sizeof(VkPhysicalDeviceVulkan11Features));
            break;
        case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES:
            memset(hdr, 0, sizeof(VkPhysicalDeviceVulkan12Features));
            break;
        case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES:
            memset(hdr, 0, sizeof(VkPhysicalDeviceVulkan13Features));
            break;
        default:
            // Unknown struct: cannot know its size, stop walking.
            next = nullptr;
            break;
        }
        hdr = next;
    }
}

extern "C" void vkGetPhysicalDeviceFeatures2(VkPhysicalDevice physicalDevice, VkPhysicalDeviceFeatures2 * pFeatures)
{
    // Resolved once; libvulkan.so is already loaded (DT_NEEDED via the 1.0
    // entry points ggml-vulkan references), so RTLD_DEFAULT finds it.
    static PFN_vkGetPhysicalDeviceFeatures2_t real_fn = nullptr;
    if (real_fn == nullptr) {
        real_fn = reinterpret_cast<PFN_vkGetPhysicalDeviceFeatures2_t>(
            dlsym(RTLD_DEFAULT, "vkGetPhysicalDeviceFeatures2"));
        if (real_fn == nullptr) {
            real_fn = reinterpret_cast<PFN_vkGetPhysicalDeviceFeatures2_t>(
                dlsym(RTLD_DEFAULT, "vkGetPhysicalDeviceFeatures2KHR"));
        }
    }

    if (real_fn != nullptr) {
        real_fn(physicalDevice, pFeatures);
        return;
    }

    // Platform loader without Vulkan 1.1 exports (API < 28): report no
    // features. ggml then disables the 1.1/coopmat code paths.
    vulkan_compat_zero_features2(pFeatures);
}
