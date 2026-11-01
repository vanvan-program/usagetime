# Preserve the narrow API exercised by isolated on-device safety checks against this minified preview.
-keep class dev.usagetime.app.data.UsageStore** { *; }
-keep class dev.usagetime.app.data.Records { *; }
-keep class dev.usagetime.core.Window { *; }
# Instrumentation and the target APK must share the same suspend/runtime ABI.
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class dev.usagetime.core.LocalRecovery** { *; }

-keep class dev.usagetime.app.data.StoredEvent** { *; }
-keep class dev.usagetime.app.data.DualCodec** { *; }
-keep class dev.usagetime.core.ClockSample** { *; }
-keep class dev.usagetime.core.Interval** { *; }
-keep class dev.usagetime.core.UsageMetric** { *; }

-keep class dev.usagetime.core.Signal** { *; }
-keep class dev.usagetime.core.EvidenceSource** { *; }
