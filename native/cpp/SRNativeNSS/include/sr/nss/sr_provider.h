#pragma once
#include <vector>
#include "sr/sr_api.h"
#include "sr/sr_modules.h"
#include "nss.h"

extern "C" {
    SR_API SRReturnCode srGetNSSUpscaleProviders(SRUpscaleProvider *outProvider);

    SR_API SRReturnCode srGetNSSUpscaleProvidersCount(uint32_t *outCount);
}
