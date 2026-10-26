// Hand-written replacement for Chromaprint's generated config.h (config.h.in): the Android build
// uses the bundled KissFFT and the internal copy of FFmpeg's resampler, nothing else.
#define HAVE_ROUND 1
#define HAVE_LRINTF 1
#define USE_INTERNAL_AVRESAMPLE 1
#define USE_KISSFFT 1
