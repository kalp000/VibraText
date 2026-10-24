# VibraText keeps no reflection-based serialization (all JSON is parsed by hand in :core),
# so the default Android + R8 rules are sufficient. Nothing needs to be kept explicitly.
#
# The notification listener service is referenced from AndroidManifest.xml, which AGP already
# keeps automatically.
