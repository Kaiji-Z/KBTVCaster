# Add project specific ProGuard rules here.
# Release builds currently ship with isMinifyEnabled=false, so these rules are
# inert today; they document what must survive if shrinking is ever turned on.
-keep class org.fourthline.cling.** { *; }

# Kotlin
-keep class kotlin.Metadata { *; }
