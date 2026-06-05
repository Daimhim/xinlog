# 保留 SDK 对外 API 与自动初始化 Provider，避免宿主 R8 误删/混淆。
-keep class com.xinxin.xinlog.XinLog { public *; }
-keep class com.xinxin.xinlog.XinLog$* { *; }
-keep class com.xinxin.xinlog.XinLogInitProvider { *; }
