# The JNI bridge (app/src/main/cpp/jni.c) finds NativeVm's native methods by
# their Java names, so neither the class nor those methods may be renamed.
# The default rules keep native method names; keep the class name too.
-keep class io.github.leukoplast1980.solderless.vm.NativeVm {
    native <methods>;
}
