# Kept narrow on purpose. What R8 would otherwise break is kept by the platform
# and by AGP: components named in the manifest. The app uses no reflection,
# serialization libraries or JNI. Add rules only for a verified need.
