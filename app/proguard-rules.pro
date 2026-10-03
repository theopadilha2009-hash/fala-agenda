# Regras de R8 da release (o debug não roda minify — ver buildTypes no build.gradle.kts).

# O R8 do release só encolhe o que é dependência: androidx, Compose, OkHttp, kotlinx. Esta
# linha não é sobre tamanho, é a rede do que o app carrega por nome — o AppDatabase_Impl que
# o Room instancia por reflexão, os $$serializer do kotlinx-serialization, os receivers, o
# AppWidgetProvider e o MainActivity do manifest (e o targetClass="...MainActivity" do
# res/xml/shortcuts.xml, que é string e o R8 não enxerga). Estreitar isso derruba o app em
# runtime num caminho que nenhum teste desta máquina cobre.
-keep class com.theopadilha.falaagenda.** { *; }

# As quatro classes abaixo são anotações do checker estático Error Prone (retenção CLASS,
# nunca lidas em runtime) que o Tink referencia nos descritores das próprias classes
# (KeysetManager, InsecureSecretKeyAccess, AesEaxKey$Builder...). Elas não existem no Android,
# e o R8 do release trata isso como erro: minifyReleaseWithR8 falha com "Missing classes
# detected while running R8". Medido no cache do Gradle desta máquina: o AAR do
# androidx.security:security-crypto 1.1.0-alpha06 não traz proguard.txt nenhum, e o único
# consumer rule que o tink-android 1.8.0 publica é META-INF/proguard/protobuf.pro (reflection
# do GeneratedMessageLite) — nada sobre o Error Prone. São as quatro linhas que o próprio AGP
# gerou em app/build/outputs/mapping/release/missing_rules.txt, por nome, sem wildcard.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi
