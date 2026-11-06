# JCA resolves algorithm providers by class name and constructs them reflectively.
-keep class org.bouncycastle.** { *; }
# SSHJ loads optional crypto implementations through SecurityUtils reflection.
-keep class net.schmizz.sshj.** { *; }
-keep class com.hierynomus.sshj.** { *; }

# Desktop-only LDAP/DANE and Kerberos integrations are unreachable: Android has
# no JNDI/JGSS, and the app exposes only password, public key and interactive SSH.
# This is generated automatically by the Android Gradle plugin.
-dontwarn javax.naming.Binding
-dontwarn javax.naming.NamingEnumeration
-dontwarn javax.naming.NamingException
-dontwarn javax.naming.directory.Attribute
-dontwarn javax.naming.directory.Attributes
-dontwarn javax.naming.directory.DirContext
-dontwarn javax.naming.directory.InitialDirContext
-dontwarn javax.naming.directory.SearchControls
-dontwarn javax.naming.directory.SearchResult
-dontwarn javax.security.auth.login.LoginContext
-dontwarn org.ietf.jgss.GSSContext
-dontwarn org.ietf.jgss.GSSCredential
-dontwarn org.ietf.jgss.GSSException
-dontwarn org.ietf.jgss.GSSManager
-dontwarn org.ietf.jgss.GSSName
-dontwarn org.ietf.jgss.MessageProp
-dontwarn org.ietf.jgss.Oid

# AndroidJUnitRunner shares this app dependency when testing the optimized APK.
# Preserve the small tracing bridge so instrumentation can start after shrinking.
-keep class androidx.tracing.Trace { *; }

# Instrumentation and the app share these runtime APIs. Keeping their entry
# points prevents R8 from erasing methods/classes used by the separate test APK.
# App/UI code and unused resources remain optimized and shrunk.
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class dev.remotegpt.client.** { *; }
-keep class kotlinx.serialization.** { *; }

# JLaTeXMath resolves predefined formulas/macros reflectively; preserve names and
# entry points used by its bundled XML/font assets in optimized release builds.
-keep class org.scilab.forge.jlatexmath.** { *; }
