# Shipped as this AAR's proguard.txt — applies to any app that bundles this SDK. Keep rules here
# narrow; anything broader stops the integrator's app shrinking code it genuinely does not use.

# Nimbus JOSE+JWT declares optional dependencies it uses when they are present. This SDK
# bundles none of them and never reaches the code paths that need them, but R8 still sees the
# references and reports them as missing classes.
-dontwarn org.bouncycastle.**
-dontwarn com.google.crypto.tink.**

# No keep rules for this SDK's own classes: nothing in it is resolved by name or by
# reflection, so an app is free to shrink whatever it does not call. If that ever changes — a
# storage implementation loaded by class name, say — the rule belongs here, with its reason.
