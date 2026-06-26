#include <string>
#include <sstream>
#include <iostream>

// Use the appropriate NDK namespace
#ifndef _LIBCPP_ABI_NAMESPACE
#define _LIBCPP_ABI_NAMESPACE __ndk1
#endif

namespace std {
  inline namespace _LIBCPP_ABI_NAMESPACE {

    // NDK 25+ missing symbol referenced by some prebuilt libraries
    __attribute__((weak))
    void __libcpp_verbose_abort(const char* format, ...) {
    }

  } // namespace _LIBCPP_ABI_NAMESPACE
} // namespace std

// Force instantiation of stream classes to provide vtables and common methods.
template class std::basic_stringbuf<char>;
template class std::basic_stringstream<char>;
template class std::basic_ostringstream<char>;

// Dummy function to ensure the above instantiations are not optimized away
// and to satisfy any direct references to these methods.
void force_stringstream_usage() {
    std::stringstream ss;
    ss << "force usage";
    std::string s = ss.str();
    std::ostringstream oss;
    oss << 123;
}
