#pragma once
#include "sr/sr_api.h"

#ifdef __cplusplus
extern "C" {
    #endif

    /// 返回 NSS 的 6 个回调。SRAPI 内部通过它驱动 NSS 上下文。
    SR_API SRUpscaleContextCallbacks srGetNSSUpscaleCallbacks();

    /* 以下 6 个函数是回调实现，导出仅为便于单独测试；
     * 正常调用路径是经由 srGetNSSUpscaleCallbacks() 返回的函数指针。 */
    SR_API SRReturnCode srNSSCreateUpscaleContext(SRUpscaleContext *context,
                                                  const SRCreateUpscaleContextDesc *desc);
    SR_API SRReturnCode srNSSInitUpscaleContext(SRUpscaleContext *context);
    SR_API SRReturnCode srNSSDestroyUpscaleContext(SRUpscaleContext *context);
    SR_API SRReturnCode srNSSQueryUpscale(SRUpscaleContext *context,
                                          SRUpscaleContextQueryResult *result,
                                          int queryType);
    SR_API SRReturnCode srNSSDispatchUpscale(SRUpscaleContext *context,
                                             const SRDispatchUpscaleDesc *desc);
    SR_API SRReturnCode srNSSShutdown();

    #ifdef __cplusplus
}
#endif
