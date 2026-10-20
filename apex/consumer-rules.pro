# 随 AAR 交给宿主的 keep 规则，宿主开启 R8 时自动生效。
# 只写 SDK 运行必需的规则，不要写 -dontobfuscate、-dontoptimize 这类全局开关，
# 否则会影响整个宿主 App。
#
# 典型需要加规则的场景：
# 1. SDK 通过反射或类名字符串访问的类
#    -keep class com.github.susanaluis877.apex.internal.SomeReflectiveClass { *; }
# 2. JNI 回调的 Java 方法（native 代码按名字查找）
#    -keepclasseswithmembernames class * { native <methods>; }
# 3. 需要 JSON 序列化的数据模型
#    -keep class com.github.susanaluis877.apex.model.** { <fields>; }
