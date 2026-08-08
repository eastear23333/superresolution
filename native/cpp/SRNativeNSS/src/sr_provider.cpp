#include "sr/nss/sr_provider.h"
#include "sr/nss/nss.h"

static SRUpscaleProvider g_providers[1];
static bool g_initialized = false;

static void ensureInitialized() {
    if (!g_initialized) {
        g_providers[0].providerId = SR_MODULES_NSS_ID;
        g_providers[0].callbacks = srGetNSSUpscaleCallbacks();
        g_initialized = true;
    }
}

extern "C" {
    SR_API SRReturnCode srGetNSSUpscaleProviders(SRUpscaleProvider *outProvider) {
        ensureInitialized();
        outProvider[0] = g_providers[0];
        return (SRReturnCode)SR_RETURN_CODE_OK;
    }

    SR_API SRReturnCode srGetNSSUpscaleProvidersCount(uint32_t *outCount) {
        ensureInitialized();
        *outCount = 1;
        return (SRReturnCode)SR_RETURN_CODE_OK;
    }
}
