# 崩溃堆栈保留行号（配合 -renamesourcefileattribute 不泄露源文件名）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 网关异常文案里的异常类名要可读（UpstreamHttpException 等按名字分流）
-keepnames class com.aigetway.tattoo7.**
