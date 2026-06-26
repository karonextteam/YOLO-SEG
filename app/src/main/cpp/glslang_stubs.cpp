#include <stddef.h>

// This file provides stubs for symbols referenced by libncnn.a and libopencv_core.a
// using their exact mangled names to ensure they match during linking.
// These are safe as long as the corresponding functionality (Vulkan, OpenMP deinit)
// is not used or not critical at runtime.

extern "C" {
    // Glslang Process management
    void _ZN7glslang15FinalizeProcessEv() {}
    bool _ZN7glslang17InitializeProcessEv() { return true; }

    // Glslang TShader stubs
    void _ZN7glslang7TShaderC1E11EShLanguage(void*, int) {}
    void _ZN7glslang7TShaderD1Ev(void*) {}
    void _ZN7glslang7TShader10getInfoLogEv(void*) {}
    void _ZN7glslang7TShader15getInfoDebugLogEv(void*) {}
    void _ZN7glslang7TShader13setEntryPointEPKc(void*, const char*) {}
    void _ZN7glslang7TShader19setSourceEntryPointEPKc(void*, const char*) {}
    void _ZN7glslang7TShader21setStringsWithLengthsEPKPKcPKii(void*, const char* const*, const int*, int) {}
    void _ZN7glslang7TShader12addProcessesERKNSt6__ndk16vectorINS1_12basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEENS6_IS8_EEEE(void*, void*) {}
    bool _ZN7glslang7TShader5parseEPK16TBuiltInResourcei8EProfilebb11EShMessagesRNS0_8IncluderE(void*, void*, int, int, bool, bool, int, void*) { return false; }

    // Glslang Conversion
    void _ZN7glslang12GlslangToSpvERKNS_13TIntermediateERNSt6__ndk16vectorIjNS3_9allocatorIjEEEEPNS_10SpvOptionsE(void*, void*, void*) {}

    // OpenMP missing symbol
    __attribute__((weak))
    void __kmpc_dispatch_deinit(void*) {}
}
