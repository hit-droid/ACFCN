// SPDX-License-Identifier: MIT
// opencl_wrapper.c - minimal forwarding OpenCL shim for ACFCN.
//
// The Khronos ICD loader bundled before v2.17 only enumerates vendor
// implementations that export the ICD entry clIcdGetPlatformIDsKHR. Adreno/
// Mali vendor libOpenCL.so are plain complete implementations WITHOUT that
// entry, so enumeration returns zero platforms and ggml-opencl silently
// falls back to CPU. This shim replaces the loader inside the APK: it
// dlopen()s the real vendor driver by absolute path and forwards the exact
// symbols libacfcn_llm imports (exported versioned via opencl_wrapper.map,
// e.g. OPENCL_1.0/2.1/3.0 - the same tags libacfcn was linked against).
// If no vendor driver can be loaded every wrapper returns null/CL error so
// the engine degrades to CPU, never crashes. NEVER dlopen bare "libOpenCL.so"
// here - it resolves to this library (infinite recursion).

#include <CL/cl.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <unistd.h>
#include <android/log.h>

#ifndef CL_API_ENTRY
#define CL_API_ENTRY
#endif
#ifndef CL_API_CALL
#define CL_API_CALL
#endif

#define CL_WRAPPER_ERR (cl_int)(-1001) /* CL_PLATFORM_NOT_FOUND_KHR */

static void *g_driver;
static int g_drv_tried;
static pthread_mutex_t g_drv_lock = PTHREAD_MUTEX_INITIALIZER;

static void *try_dlopen(const char *path) {
    if (!path || !*path) return NULL;
    void *h = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    if (h) __android_log_print(ANDROID_LOG_INFO, "ACFCN-OCL", "driver loaded: %s", path);
    return h;
}

static void collect_paths(char (*out)[300], int *n, const char *env) {
    if (!env || !*env) return;
    size_t i = 0, len = strlen(env);
    while (i < len && *n < 8) {
        size_t j = i;
        while (j < len && env[j] != ':' && env[j] != ' ' && env[j] != '\t' && env[j] != '\n' && env[j] != '\r')
            j++;
        if (j > i && j - i < sizeof(out[0])) {
            memcpy(out[*n], env + i, j - i);
            out[*n][j - i] = 0;
            (*n)++;
        }
        i = j;
        while (i < len && (env[i] == ':' || env[i] == ' ' || env[i] == '\t' || env[i] == '\n' || env[i] == '\r'))
            i++;
    }
}

static void driver_init(void) {
    char paths[16][300];
    int n = 0;
    collect_paths(paths, &n, getenv("ACFCN_OCL_DRIVER"));
    collect_paths(paths, &n, getenv("OCL_ICD_FILENAMES"));
    static const char *fixed[] = {
        "/vendor/lib64/libOpenCL.so",
        "/vendor/lib64/libOpenCL_adreno.so",
        "/vendor/lib64/libclcore.so",
        "/system/vendor/lib64/libOpenCL.so",
        "/system/lib64/libOpenCL.so",
        "/system/lib64/libOpenCL_adreno.so",
    };
    for (unsigned k = 0; k < sizeof(fixed) / sizeof(fixed[0]) && n < 16; k++) {
        memcpy(paths[n], fixed[k], strlen(fixed[k]) + 1);
        n++;
    }
    for (int k = 0; k < n && !g_driver; k++)
        g_driver = try_dlopen(paths[k]);
    if (!g_driver)
        __android_log_print(ANDROID_LOG_WARN, "ACFCN-OCL",
                            "no vendor driver among %d paths; CPU fallback", n);
}

static void *ocl_sym(const char *name) {
    pthread_mutex_lock(&g_drv_lock);
    if (!g_drv_tried) {
        g_drv_tried = 1;
        driver_init();
    }
    void *h = g_driver;
    pthread_mutex_unlock(&g_drv_lock);
    return h ? dlsym(h, name) : NULL;
}

typedef cl_int CL_API_CALL clBuildProgram_fn_t(cl_program program, cl_uint num_devices, const cl_device_id * device_list, const char * options, void (CL_CALLBACK * pfn_notify)(cl_program program, void * user_data), void * user_data);

CL_API_ENTRY cl_int CL_API_CALL clBuildProgram(cl_program program, cl_uint num_devices, const cl_device_id * device_list, const char * options, void (CL_CALLBACK * pfn_notify)(cl_program program, void * user_data), void * user_data) {
    static clBuildProgram_fn_t *f;
    if (!f) *(void **)&f = ocl_sym("clBuildProgram");
    if (!f) return (cl_int)CL_WRAPPER_ERR;
    return f(program, num_devices, device_list, options, pfn_notify, user_data);
}

typedef cl_mem CL_API_CALL clCreateBuffer_fn_t(cl_context context, cl_mem_flags flags, size_t size, void * host_ptr, cl_int * errcode_ret);

CL_API_ENTRY cl_mem CL_API_CALL clCreateBuffer(cl_context context, cl_mem_flags flags, size_t size, void * host_ptr, cl_int * errcode_ret) {
    static clCreateBuffer_fn_t *f;
    if (!f) *(void **)&f = ocl_sym("clCreateBuffer");
    if (!f) return (cl_mem)0;
    return f(context, flags, size, host_ptr, errcode_ret);
}

typedef cl_mem CL_API_CALL clCreateBufferWithProperties_fn_t(cl_context context, const cl_mem_properties * properties, cl_mem_flags flags, size_t size, void * host_ptr, cl_int * errcode_ret);

CL_API_ENTRY cl_mem CL_API_CALL clCreateBufferWithProperties(cl_context context, const cl_mem_properties * properties, cl_mem_flags flags, size_t size, void * host_ptr, cl_int * errcode_ret) {
    static clCreateBufferWithProperties_fn_t *f;
    if (!f) *(void **)&f = ocl_sym("clCreateBufferWithProperties");
    if (!f) return (cl_mem)0;
    return f(context, properties, flags, size, host_ptr, errcode_ret);
}

typedef cl_command_queue CL_API_CALL clCreateCommandQueue_fn_t(cl_context context, cl_device_id device, cl_command_queue_properties properties, cl_int * errcode_ret);

CL_API_ENTRY cl_command_queue CL_API_CALL clCreateCommandQueue(cl_context context, cl_device_id device, cl_command_queue_properties properties, cl_int * errcode_ret) {
    static clCreateCommandQueue_fn_t *f;
    if (!f) *(void **)&f = ocl_sym("clCreateCommandQueue");
    if (!f) return (cl_command_queue)0;
    return f(context, device, properties, errcode_ret);
}

typedef cl_context CL_API_CALL clCreateContext_fn_t(const cl_context_properties * properties, cl_uint num_devices, const cl_device_id * devices, void (CL_CALLBACK * pfn_notify)(const char * errinfo, const void * private_info, size_t cb, void * user_data), void * user_data, cl_int * errcode_ret);

CL_API_ENTRY cl_context CL_API_CALL clCreateContext(const cl_context_properties * properties, cl_uint num_devices, const cl_device_id * devices, void (CL_CALLBACK * pfn_notify)(const char * errinfo, const void * private_info, size_t cb, void * user_data), void * user_data, cl_int * errcode_ret) {
    static clCreateContext_fn_t *f;
    if (!f) *(void **)&f = ocl_sym("clCreateContext");
    if (!f) return (cl_context)0;
    return f(properties, num_devices, devices, pfn_notify, user_data, errcode_ret);
}

typedef cl_mem CL_API_CALL clCreateImage_fn_t(cl_context context, cl_mem_flags flags, const cl_image_format * image_format, const cl_image_desc * image_desc, void * host_ptr, cl_int * errcode_ret);

CL_API_ENTRY cl_mem CL_API_CALL clCreateImage(cl_context context, cl_mem_flags flags, const cl_image_format * image_format, const cl_image_desc * image_desc, void * host_ptr, cl_int * errcode_ret) {
    static clCreateImage_fn_t *f;
    if (!f) *(void **)&f = ocl_sym("clCreateImage");
    if (!f) return (cl_mem)0;
    return f(context, flags, image_format, image_desc, host_ptr, errcode_ret);
}

typedef cl_kernel CL_API_CALL clCreateKernel_fn_t(cl_program program, const char * kernel_name, cl_int * errcode_ret);

CL_API_ENTRY cl_kernel CL_API_CALL clCreateKernel(cl_program program, const char * kernel_name, cl_int * errcode_ret) {
    static clCreateKernel_fn_t *f;
    if (!f) *(void **)&f = ocl_sym("clCreateKernel");
    if (!f) return (cl_kernel)0;
    return f(program, kernel_name, errcode_ret);
}

typedef cl_program CL_API_CALL clCreateProgramWithBinary_fn_t(cl_context context, cl_uint num_devices, const cl_device_id * device_list, const size_t * lengths, const unsigned char ** binaries, cl_int * binary_status, cl_int * errcode_ret);

CL_API_ENTRY cl_program CL_API_CALL clCreateProgramWithBinary(cl_context context, cl_uint num_devices, const cl_device_id * device_list, const size_t * lengths, const unsigned char ** binaries, cl_int * binary_status, cl_int * errcode_ret) {
    static clCreateProgramWithBinary_fn_t *f;
    if (!f) *(void **)&f = ocl_sym("clCreateProgramWithBinary");
    if (!f) return (cl_program)0;
    return f(context, num_devices, device_list, lengths, binaries, binary_status, errcode_ret);
}

typedef cl_program CL_API_CALL clCreateProgramWithSource_fn_t(cl_context context, cl_uint count, const char ** strings, const size_t * lengths, cl_int * errcode_ret);

CL_API_ENTRY cl_program CL_API_CALL clCreateProgramWithSource(cl_context context, cl_uint count, const char ** strings, const size_t * lengths, cl_int * errcode_ret) {
    static clCreateProgramWithSource_fn_t *f;
    if (!f) *(void **)&f = ocl_sym("clCreateProgramWithSource");
    if (!f) return (cl_program)0;
    return f(context, count, strings, lengths, errcode_ret);
}

typedef cl_mem CL_API_CALL clCreateSubBuffer_fn_t(cl_mem buffer, cl_mem_flags flags, cl_buffer_create_type buffer_create_type, const void * buffer_create_info, cl_int * errcode_ret);

CL_API_ENTRY cl_mem CL_API_CALL clCreateSubBuffer(cl_mem buffer, cl_mem_flags flags, cl_buffer_create_type buffer_create_type, const void * buffer_create_info, cl_int * errcode_ret) {
    static clCreateSubBuffer_fn_t *f;
    if (!f) *(void **)&f = ocl_sym("clCreateSubBuffer");
    if (!f) return (cl_mem)0;
    return f(buffer, flags, buffer_create_type, buffer_create_info, errcode_ret);
}

typedef cl_int CL_API_CALL clEnqueueBarrierWithWaitList_fn_t(cl_command_queue command_queue, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event);

CL_API_ENTRY cl_int CL_API_CALL clEnqueueBarrierWithWaitList(cl_command_queue command_queue, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event) {
    static clEnqueueBarrierWithWaitList_fn_t *f;
    if (!f) *(void **)&f = ocl_sym("clEnqueueBarrierWithWaitList");
    if (!f) return (cl_int)CL_WRAPPER_ERR;
    return f(command_queue, num_events_in_wait_list, event_wait_list, event);
}

typedef cl_int CL_API_CALL clEnqueueCopyBuffer_fn_t(cl_command_queue command_queue, cl_mem src_buffer, cl_mem dst_buffer, size_t src_offset, size_t dst_offset, size_t size, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event);

CL_API_ENTRY cl_int CL_API_CALL clEnqueueCopyBuffer(cl_command_queue command_queue, cl_mem src_buffer, cl_mem dst_buffer, size_t src_offset, size_t dst_offset, size_t size, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event) {
    static clEnqueueCopyBuffer_fn_t *f;
    if (!f) *(void **)&f = ocl_sym("clEnqueueCopyBuffer");
    if (!f) return (cl_int)CL_WRAPPER_ERR;
    return f(command_queue, src_buffer, dst_buffer, src_offset, dst_offset, size, num_events_in_wait_list, event_wait_list, event);
}

typedef cl_int CL_API_CALL clEnqueueFillBuffer_fn_t(cl_command_queue command_queue, cl_mem buffer, const void * pattern, size_t pattern_size, size_t offset, size_t size, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event);

CL_API_ENTRY cl_int CL_API_CALL clEnqueueFillBuffer(cl_command_queue command_queue, cl_mem buffer, const void * pattern, size_t pattern_size, size_t offset, size_t size, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event) {
    static clEnqueueFillBuffer_fn_t *f;
    if (!f) *(void **)&f = ocl_sym("clEnqueueFillBuffer");
    if (!f) return (cl_int)CL_WRAPPER_ERR;
    return f(command_queue, buffer, pattern, pattern_size, offset, size, num_events_in_wait_list, event_wait_list, event);
}

typedef void * CL_API_CALL clEnqueueMapBuffer_fn_t(cl_command_queue command_queue, cl_mem buffer, cl_bool blocking_map, cl_map_flags map_flags, size_t offset, size_t size, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event, cl_int * errcode_ret);

CL_API_ENTRY void * CL_API_CALL clEnqueueMapBuffer(cl_command_queue command_queue, cl_mem buffer, cl_bool blocking_map, cl_map_flags map_flags, size_t offset, size_t size, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event, cl_int * errcode_ret) {
    static clEnqueueMapBuffer_fn_t *f;
    if (!f) *(void **)&f = ocl_sym("clEnqueueMapBuffer");
    if (!f) return (void *)0;
    return f(command_queue, buffer, blocking_map, map_flags, offset, size, num_events_in_wait_list, event_wait_list, event, errcode_ret);
}

typedef cl_int CL_API_CALL clEnqueueMarkerWithWaitList_fn_t(cl_command_queue command_queue, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event);

CL_API_ENTRY cl_int CL_API_CALL clEnqueueMarkerWithWaitList(cl_command_queue command_queue, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event) {
    static clEnqueueMarkerWithWaitList_fn_t *f;
    if (!f) *(void **)&f = ocl_sym("clEnqueueMarkerWithWaitList");
    if (!f) return (cl_int)CL_WRAPPER_ERR;
    return f(command_queue, num_events_in_wait_list, event_wait_list, event);
}

typedef cl_int CL_API_CALL clEnqueueNDRangeKernel_fn_t(cl_command_queue command_queue, cl_kernel kernel, cl_uint work_dim, const size_t * global_work_offset, const size_t * global_work_size, const size_t * local_work_size, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event);

CL_API_ENTRY cl_int CL_API_CALL clEnqueueNDRangeKernel(cl_command_queue command_queue, cl_kernel kernel, cl_uint work_dim, const size_t * global_work_offset, const size_t * global_work_size, const size_t * local_work_size, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event) {
    static clEnqueueNDRangeKernel_fn_t *f;
    if (!f) *(void **)&f = ocl_sym("clEnqueueNDRangeKernel");
    if (!f) return (cl_int)CL_WRAPPER_ERR;
    return f(command_queue, kernel, work_dim, global_work_offset, global_work_size, local_work_size, num_events_in_wait_list, event_wait_list, event);
}

typedef cl_int CL_API_CALL clEnqueueReadBuffer_fn_t(cl_command_queue command_queue, cl_mem buffer, cl_bool blocking_read, size_t offset, size_t size, void * ptr, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event);

CL_API_ENTRY cl_int CL_API_CALL clEnqueueReadBuffer(cl_command_queue command_queue, cl_mem buffer, cl_bool blocking_read, size_t offset, size_t size, void * ptr, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event) {
    static clEnqueueReadBuffer_fn_t *f;
    if (!f) *(void **)&f = ocl_sym("clEnqueueReadBuffer");
    if (!f) return (cl_int)CL_WRAPPER_ERR;
    return f(command_queue, buffer, blocking_read, offset, size, ptr, num_events_in_wait_list, event_wait_list, event);
}

typedef cl_int CL_API_CALL clEnqueueUnmapMemObject_fn_t(cl_command_queue command_queue, cl_mem memobj, void * mapped_ptr, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event);

CL_API_ENTRY cl_int CL_API_CALL clEnqueueUnmapMemObject(cl_command_queue command_queue, cl_mem memobj, void * mapped_ptr, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event) {
    static clEnqueueUnmapMemObject_fn_t *f;
    if (!f) *(void **)&f = ocl_sym("clEnqueueUnmapMemObject");
    if (!f) return (cl_int)CL_WRAPPER_ERR;
    return f(command_queue, memobj, mapped_ptr, num_events_in_wait_list, event_wait_list, event);
}

typedef cl_int CL_API_CALL clEnqueueWriteBuffer_fn_t(cl_command_queue command_queue, cl_mem buffer, cl_bool blocking_write, size_t offset, size_t size, const void * ptr, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event);

CL_API_ENTRY cl_int CL_API_CALL clEnqueueWriteBuffer(cl_command_queue command_queue, cl_mem buffer, cl_bool blocking_write, size_t offset, size_t size, const void * ptr, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event) {
    static clEnqueueWriteBuffer_fn_t *f;
    if (!f) *(void **)&f = ocl_sym("clEnqueueWriteBuffer");
    if (!f) return (cl_int)CL_WRAPPER_ERR;
    return f(command_queue, buffer, blocking_write, offset, size, ptr, num_events_in_wait_list, event_wait_list, event);
}

typedef cl_int CL_API_CALL clFinish_fn_t(cl_command_queue command_queue);

CL_API_ENTRY cl_int CL_API_CALL clFinish(cl_command_queue command_queue) {
    static clFinish_fn_t *f;
    if (!f) *(void **)&f = ocl_sym("clFinish");
    if (!f) return (cl_int)CL_WRAPPER_ERR;
    return f(command_queue);
}

typedef cl_int CL_API_CALL clFlush_fn_t(cl_command_queue command_queue);

CL_API_ENTRY cl_int CL_API_CALL clFlush(cl_command_queue command_queue) {
    static clFlush_fn_t *f;
    if (!f) *(void **)&f = ocl_sym("clFlush");
    if (!f) return (cl_int)CL_WRAPPER_ERR;
    return f(command_queue);
}

typedef cl_int CL_API_CALL clGetDeviceIDs_fn_t(cl_platform_id platform, cl_device_type device_type, cl_uint num_entries, cl_device_id * devices, cl_uint * num_devices);

CL_API_ENTRY cl_int CL_API_CALL clGetDeviceIDs(cl_platform_id platform, cl_device_type device_type, cl_uint num_entries, cl_device_id * devices, cl_uint * num_devices) {
    static clGetDeviceIDs_fn_t *f;
    if (!f) *(void **)&f = ocl_sym("clGetDeviceIDs");
    if (!f) return (cl_int)CL_WRAPPER_ERR;
    return f(platform, device_type, num_entries, devices, num_devices);
}

typedef cl_int CL_API_CALL clGetDeviceInfo_fn_t(cl_device_id device, cl_device_info param_name, size_t param_value_size, void * param_value, size_t * param_value_size_ret);

CL_API_ENTRY cl_int CL_API_CALL clGetDeviceInfo(cl_device_id device, cl_device_info param_name, size_t param_value_size, void * param_value, size_t * param_value_size_ret) {
    static clGetDeviceInfo_fn_t *f;
    if (!f) *(void **)&f = ocl_sym("clGetDeviceInfo");
    if (!f) return (cl_int)CL_WRAPPER_ERR;
    return f(device, param_name, param_value_size, param_value, param_value_size_ret);
}

typedef cl_int CL_API_CALL clGetKernelSubGroupInfo_fn_t(cl_kernel kernel, cl_device_id device, cl_kernel_sub_group_info param_name, size_t input_value_size, const void* input_value, size_t param_value_size, void* param_value, size_t* param_value_size_ret);

CL_API_ENTRY cl_int CL_API_CALL clGetKernelSubGroupInfo(cl_kernel kernel, cl_device_id device, cl_kernel_sub_group_info param_name, size_t input_value_size, const void* input_value, size_t param_value_size, void* param_value, size_t* param_value_size_ret) {
    static clGetKernelSubGroupInfo_fn_t *f;
    if (!f) *(void **)&f = ocl_sym("clGetKernelSubGroupInfo");
    if (!f) return (cl_int)CL_WRAPPER_ERR;
    return f(kernel, device, param_name, input_value_size, input_value, param_value_size, param_value, param_value_size_ret);
}

typedef cl_int CL_API_CALL clGetKernelWorkGroupInfo_fn_t(cl_kernel kernel, cl_device_id device, cl_kernel_work_group_info param_name, size_t param_value_size, void * param_value, size_t * param_value_size_ret);

CL_API_ENTRY cl_int CL_API_CALL clGetKernelWorkGroupInfo(cl_kernel kernel, cl_device_id device, cl_kernel_work_group_info param_name, size_t param_value_size, void * param_value, size_t * param_value_size_ret) {
    static clGetKernelWorkGroupInfo_fn_t *f;
    if (!f) *(void **)&f = ocl_sym("clGetKernelWorkGroupInfo");
    if (!f) return (cl_int)CL_WRAPPER_ERR;
    return f(kernel, device, param_name, param_value_size, param_value, param_value_size_ret);
}

typedef cl_int CL_API_CALL clGetPlatformIDs_fn_t(cl_uint num_entries, cl_platform_id * platforms, cl_uint * num_platforms);

CL_API_ENTRY cl_int CL_API_CALL clGetPlatformIDs(cl_uint num_entries, cl_platform_id * platforms, cl_uint * num_platforms) {
    static clGetPlatformIDs_fn_t *f;
    if (!f) *(void **)&f = ocl_sym("clGetPlatformIDs");
    if (!f) return (cl_int)CL_WRAPPER_ERR;
    return f(num_entries, platforms, num_platforms);
}

typedef cl_int CL_API_CALL clGetPlatformInfo_fn_t(cl_platform_id platform, cl_platform_info param_name, size_t param_value_size, void * param_value, size_t * param_value_size_ret);

CL_API_ENTRY cl_int CL_API_CALL clGetPlatformInfo(cl_platform_id platform, cl_platform_info param_name, size_t param_value_size, void * param_value, size_t * param_value_size_ret) {
    static clGetPlatformInfo_fn_t *f;
    if (!f) *(void **)&f = ocl_sym("clGetPlatformInfo");
    if (!f) return (cl_int)CL_WRAPPER_ERR;
    return f(platform, param_name, param_value_size, param_value, param_value_size_ret);
}

typedef cl_int CL_API_CALL clGetProgramBuildInfo_fn_t(cl_program program, cl_device_id device, cl_program_build_info param_name, size_t param_value_size, void * param_value, size_t * param_value_size_ret);

CL_API_ENTRY cl_int CL_API_CALL clGetProgramBuildInfo(cl_program program, cl_device_id device, cl_program_build_info param_name, size_t param_value_size, void * param_value, size_t * param_value_size_ret) {
    static clGetProgramBuildInfo_fn_t *f;
    if (!f) *(void **)&f = ocl_sym("clGetProgramBuildInfo");
    if (!f) return (cl_int)CL_WRAPPER_ERR;
    return f(program, device, param_name, param_value_size, param_value, param_value_size_ret);
}

typedef cl_int CL_API_CALL clGetProgramInfo_fn_t(cl_program program, cl_program_info param_name, size_t param_value_size, void * param_value, size_t * param_value_size_ret);

CL_API_ENTRY cl_int CL_API_CALL clGetProgramInfo(cl_program program, cl_program_info param_name, size_t param_value_size, void * param_value, size_t * param_value_size_ret) {
    static clGetProgramInfo_fn_t *f;
    if (!f) *(void **)&f = ocl_sym("clGetProgramInfo");
    if (!f) return (cl_int)CL_WRAPPER_ERR;
    return f(program, param_name, param_value_size, param_value, param_value_size_ret);
}

typedef cl_int CL_API_CALL clReleaseEvent_fn_t(cl_event event);

CL_API_ENTRY cl_int CL_API_CALL clReleaseEvent(cl_event event) {
    static clReleaseEvent_fn_t *f;
    if (!f) *(void **)&f = ocl_sym("clReleaseEvent");
    if (!f) return (cl_int)CL_WRAPPER_ERR;
    return f(event);
}

typedef cl_int CL_API_CALL clReleaseKernel_fn_t(cl_kernel kernel);

CL_API_ENTRY cl_int CL_API_CALL clReleaseKernel(cl_kernel kernel) {
    static clReleaseKernel_fn_t *f;
    if (!f) *(void **)&f = ocl_sym("clReleaseKernel");
    if (!f) return (cl_int)CL_WRAPPER_ERR;
    return f(kernel);
}

typedef cl_int CL_API_CALL clReleaseMemObject_fn_t(cl_mem memobj);

CL_API_ENTRY cl_int CL_API_CALL clReleaseMemObject(cl_mem memobj) {
    static clReleaseMemObject_fn_t *f;
    if (!f) *(void **)&f = ocl_sym("clReleaseMemObject");
    if (!f) return (cl_int)CL_WRAPPER_ERR;
    return f(memobj);
}

typedef cl_int CL_API_CALL clReleaseProgram_fn_t(cl_program program);

CL_API_ENTRY cl_int CL_API_CALL clReleaseProgram(cl_program program) {
    static clReleaseProgram_fn_t *f;
    if (!f) *(void **)&f = ocl_sym("clReleaseProgram");
    if (!f) return (cl_int)CL_WRAPPER_ERR;
    return f(program);
}

typedef cl_int CL_API_CALL clSetKernelArg_fn_t(cl_kernel kernel, cl_uint arg_index, size_t arg_size, const void * arg_value);

CL_API_ENTRY cl_int CL_API_CALL clSetKernelArg(cl_kernel kernel, cl_uint arg_index, size_t arg_size, const void * arg_value) {
    static clSetKernelArg_fn_t *f;
    if (!f) *(void **)&f = ocl_sym("clSetKernelArg");
    if (!f) return (cl_int)CL_WRAPPER_ERR;
    return f(kernel, arg_index, arg_size, arg_value);
}

typedef cl_int CL_API_CALL clWaitForEvents_fn_t(cl_uint num_events, const cl_event * event_list);

CL_API_ENTRY cl_int CL_API_CALL clWaitForEvents(cl_uint num_events, const cl_event * event_list) {
    static clWaitForEvents_fn_t *f;
    if (!f) *(void **)&f = ocl_sym("clWaitForEvents");
    if (!f) return (cl_int)CL_WRAPPER_ERR;
    return f(num_events, event_list);
}
