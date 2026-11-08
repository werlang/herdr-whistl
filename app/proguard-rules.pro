# Herdr Whistl R8 rules. Intentionally empty: no reflection or serialization yet.
# Add keep rules here only with a concrete reason, never blanket keeps.

# BouncyCastle (phase 4 r1, SPEC §6): sshj reaches its crypto through JCA lookups by name ("BC", "Ed25519", ...), and the
# provider registers each algorithm as a class name string that is only loaded when it is used. R8 cannot see those
# references, so the provider class and the JCA service classes under jcajce.provider are kept. Nothing else of
# BouncyCastle is kept by these rules; the lightweight API that KeyManager calls is kept by reachability.
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { <init>(); }
-keep class org.bouncycastle.jcajce.provider.** { *; }
