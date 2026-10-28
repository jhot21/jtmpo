# R8 full mode with proguard-android-optimize.txt. The app has no reflective
# entry points of its own: Room's generated code, kotlinx.serialization's
# generated serializers and the click sample (R.raw.digital_tock_48k) are all
# statically referenced, so the default rules plus library consumer rules
# cover them. Add keeps here if a future dependency reflects over its own
# types (see meld's proguard-rules.pro for Gson/Tasker examples).
