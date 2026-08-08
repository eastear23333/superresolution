#pragma once
#include "sr/sr_api.h"
#include "sr/sr_modules.h"

extern "C" {
    SR_API SRReturnCode srGetNSSUpscaleProviders(SRUpscaleProvider *outProvider);
    SR_API SRReturnCode srGetNSSUpscaleProvidersCount(uint32_t *outCount);
}
