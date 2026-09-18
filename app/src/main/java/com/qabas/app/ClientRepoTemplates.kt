package com.qabas.app

/**
 * قوالب GitHub مجرّبة تُحقن في مستودع العميل الجديد **بدل توليد الذكاء العشوائي**:
 * - `android-ci.yml`: بناء APK حقيقي بلا gradle-wrapper (عبر setup-gradle) + إصدار تلقائي عند الوسوم.
 * - `dependabot.yml`: تحديثات أسبوعية للمكتبات.
 * - `release-drafter.yml` + الإعداد: مسودات إصدارات من رسائل الـ commit.
 *
 * القالب الوحيد المتغير هو اسم الحزمة — كل شيء آخر ثابت ومضمون.
 */
object ClientRepoTemplates {

    /**
     * CI مستخلص من قبس نفسه (مجرّب على بنايات خضراء حقيقية):
     * JDK 17 + تثبيت SDK + إصلاح الـ wrapper + بناء + رفع APK + إصدار الوسوم.
     * حُذفت منه خصوصيات قبس (الـ delta والـ xdelta وحقن الأسرار) — بقي العظم الصلب.
     */
    fun androidCiYml(): String = """
name: Android CI
on:
  push:
    branches: [main, master, dev-*]
    tags: ['v*']
    paths:
      - 'app/src/**'
      - 'app/build.gradle*'
      - 'build.gradle*'
      - 'settings.gradle*'
      - 'gradle/**'
      - 'gradlew'
  pull_request:
  workflow_dispatch:

concurrency:
  group: build-${'$'}{{ github.ref }}
  cancel-in-progress: true

jobs:
  build:
    runs-on: ubuntu-latest
    timeout-minutes: 45
    permissions:
      contents: write
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0
      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17
          cache: gradle
      - name: Setup Android SDK
        run: |
          echo "ANDROID_HOME=${'$'}{ANDROID_HOME:-/usr/local/lib/android/sdk}" >> "${'$'}GITHUB_ENV"
          yes | "${'$'}ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses || true
          "${'$'}ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --install "platform-tools" "build-tools;34.0.0" "platforms;android-34" 2>/dev/null || true
      - name: Fix gradle-wrapper.jar
        run: |
          mkdir -p gradle/wrapper
          curl -sL -o gradle/wrapper/gradle-wrapper.jar \
            "https://raw.githubusercontent.com/gradle/gradle/v8.7.0/gradle/wrapper/gradle-wrapper.jar"
          chmod +x gradlew
      - name: Set up Gradle 8.7 (no wrapper script in fresh repos)
        uses: gradle/actions/setup-gradle@v3
        with:
          gradle-version: 8.7
      - name: Assemble Debug APK
        env:
          BUILD_NUMBER: ${'$'}{{ github.run_number }}
        run: gradle assembleDebug --stacktrace --no-daemon --max-workers=2 --build-cache
      - name: Upload APK artifact
        if: success()
        uses: actions/upload-artifact@v4
        with:
          name: app-debug
          path: app/build/outputs/apk/debug/*.apk
          if-no-files-found: warn
          retention-days: 14
      - name: Distribute to client testers (Firebase)
        if: success() && secrets.FIREBASE_APP_ID != ''
        uses: wzieba/Firebase-Distribution-Github-Action@v1
        with:
          appId: ${'$'}{{ secrets.FIREBASE_APP_ID }}
          serviceCredentialsFileContent: ${'$'}{{ secrets.FIREBASE_SERVICE_ACCOUNT }}
          groups: client-testers
          file: app/build/outputs/apk/debug/app-debug.apk
      - name: Publish tag release
        if: success() && startsWith(github.ref, 'refs/tags/v')
        env:
          GH_TOKEN: ${'$'}{{ secrets.GITHUB_TOKEN }}
        run: |
          NEW_APK=$(ls app/build/outputs/apk/debug/*.apk | head -1)
          gh release create "${'$'}{{ github.ref_name }}" --title "${'$'}{{ github.ref_name }}" --notes "بناء تلقائي من قبس" "${'$'}NEW_APK" --clobber 2>/dev/null || gh release upload "${'$'}{{ github.ref_name }}" "${'$'}NEW_APK" --clobber
      - name: On failure hint
        if: failure()
        run: echo "ابحث في اللوج عن: e: file:// أو Unresolved reference"
""".trimIndent()

    /** gitignore أندرويد قياسي — يحمي المفاتيح والمخرجات منذ اليوم الأول. */    fun gitignore(): String = """
# مخرجات البناء
/build/
/app/build/
*.apk
*.aab
# Gradle
.gradle/
local.properties
# المفاتيح والأسرار — لا تُرفع أبداً
*.jks
*.keystore
google-services.json
.env
# IDE
.idea/
*.iml
.DS_Store
""".trimIndent()

    /**
     * دليل المراقبة (measure-sh/measure — بديل Crashlytics المجاني):
     * خطوات ربط التطبيق بلوحة تتبع الانهيارات والأداء + بيعها كخدمة شهرية.
     */
    fun monitoringDoc(): String = """
# المراقبة والدعم المستمر 🩺

التطبيق مجهّز للربط مع [measure](https://github.com/measure-sh/measure) —
بديل Firebase Crashlytics مفتوح المصدر ومجاني (انهيارات + ANR + أداء + بلاغات بالاهتزاز).

## التفعيل (مرة واحدة لكل عميل)
1. أنشئ حساباً في Measure Cloud (أو استضف ذاتياً) وأنشئ تطبيقاً جديداً.
2. انسخ App ID وضعه مكان `MEASURE_APP_ID` في `MyApp.kt`.
3. أضف الاعتماد في `app/build.gradle`:
   `implementation("sh.measure:measure-android:0.x.y")` (راجع أحدث نسخة في مستودع measure).
4. ابنِ نسخة جديدة — ستتدفق الانهيارات للوحة فور حدوثها.

## كنموذج دخل
بِع العميل «باقة مراقبة شهرية»: تنبيه فوري عند أي انهيار + تقرير شهري + إصلاحات عاجلة.
التكلفة عليك صفر (measure مجاني) — القيمة كلها في المتابعة.
""".trimIndent()

    fun dependabotYml(): String = """version: 2
updates:
  - package-ecosystem: gradle
    directory: /
    schedule:
      interval: weekly
    open-pull-requests-limit: 5
  - package-ecosystem: github-actions
    directory: /
    schedule:
      interval: weekly
""".trimIndent()

    fun releaseDrafterWorkflow(): String = """
name: Release Drafter
on:
  push:
    branches: [main, master]
  pull_request:
    types: [opened, reopened, synchronize]
permissions:
  contents: write
  pull-requests: write
jobs:
  draft:
    runs-on: ubuntu-latest
    steps:
      - uses: release-drafter/release-drafter@v6
        with:
          config-name: release-drafter.yml
        env:
          GITHUB_TOKEN: ${'$'}{{ secrets.GITHUB_TOKEN }}
""".trimIndent()

    fun releaseDrafterConfig(): String = """
name-template: 'v${'$'}NEXT_MINOR_VERSION'
tag-template: 'v${'$'}NEXT_MINOR_VERSION'
categories:
  - title: '✨ جديد'
    labels: [feature, feat]
  - title: '🐛 إصلاحات'
    labels: [fix, bug]
  - title: '🛠️ صيانة'
    labels: [chore, deps]
change-template: '- ${'$'}TITLE @${'$'}AUTHOR (#${'$'}NUMBER)'
template: |
  ## التغييرات
  ${'$'}CHANGES
""".trimIndent()

    /** كل ملفات القوالب (المسار ← المحتوى) للحقن المباشر. */
    fun all(): List<Pair<String, String>> = listOf(
        ".github/workflows/android-ci.yml" to androidCiYml(),
        ".github/workflows/generate-keystore.yml" to generateKeystoreWorkflow(),
        ".github/workflows/release-drafter.yml" to releaseDrafterWorkflow(),
        ".github/release-drafter.yml" to releaseDrafterConfig(),
        ".gitignore" to gitignore(),
        "docs/MONITORING.md" to monitoringDoc(),
        ".github/dependabot.yml" to dependabotYml(),
        "scripts/rename-client-app.sh" to renameScript()
    )

    /**
     * توليد keystore للتوقيع وحفظه كـ Secrets تلقائياً (مستوحى من soraiyu/android-apk-template).
     * شرط واحد: secret باسم GH_PAT (رمز PAT بصلاحية repo) يُضاف يدوياً مرة واحدة للمستودع.
     */
    fun generateKeystoreWorkflow(): String = """
name: Generate Keystores
on:
  workflow_dispatch:
jobs:
  keystore:
    runs-on: ubuntu-latest
    steps:
      - name: Generate release keystore
        run: |
          keytool -genkeypair -keystore release.jks -alias client -keyalg RSA -keysize 2048 -validity 10000 -storepass android -keypass android -dname "CN=Client App, OU=Qabas Agency, O=Client, C=SA"
          echo "RELEASE_B64=$(base64 -w0 release.jks)" >> ${'$'}GITHUB_ENV
          keytool -genkeypair -keystore debug.jks -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -storepass android -keypass android -dname "CN=Android Debug, OU=Qabas Agency, O=Client, C=SA"
          echo "DEBUG_B64=$(base64 -w0 debug.jks)" >> ${'$'}GITHUB_ENV
      - name: Save as repo secrets
        env:
          GH_PAT: ${'$'}{{ secrets.GH_PAT }}
        run: |
          if [ -z "${'$'}GH_PAT" ]; then echo "أضف secret باسم GH_PAT أولاً (رمز PAT بصلاحية repo)"; exit 1; fi
          echo "${'$'}RELEASE_B64" | gh secret set ANDROID_KEYSTORE_BASE64 --repo "${'$'}GITHUB_REPOSITORY" --app actions -
          gh secret set ANDROID_KEYSTORE_PASSWORD --repo "${'$'}GITHUB_REPOSITORY" --app actions --body android
          gh secret set ANDROID_KEY_ALIAS --repo "${'$'}GITHUB_REPOSITORY" --app actions --body client
          gh secret set ANDROID_KEY_PASSWORD --repo "${'$'}GITHUB_REPOSITORY" --app actions --body android
          echo "${'$'}DEBUG_B64" | gh secret set ANDROID_DEBUG_KEYSTORE_BASE64 --repo "${'$'}GITHUB_REPOSITORY" --app actions -
          echo "تم حفظ مفاتيح التوقيع ✅"
""".trimIndent()

    /**
     * سكربت إعادة التسمية (مستوحى من ConsultMe/kikin81): يبدّل الحزمة واسم التطبيق.
     * الاستخدام: bash scripts/rename-client-app.sh com.example.shop "متجر العميل"
     */
    fun renameScript(): String = """
#!/bin/bash
# إعادة تسمية مشروع العميل: الحزمة + اسم التطبيق
# bash scripts/rename-client-app.sh com.example.shop "متجر العميل"
set -euo pipefail
NEW_PKG="${'$'}1"
NEW_NAME="${'$'}2"
OLD_PKG="com.client.app"
OLD_PATH="com/client/app"
NEW_PATH=$(echo "${'$'}NEW_PKG" | tr '.' '/')

grep -rl "${'$'}OLD_PKG" --include="*.kt" --include="*.kts" --include="*.xml" --include="*.gradle" . | xargs sed -i "s/${'$'}OLD_PKG/${'$'}NEW_PKG/g" || true
if [ -n "${'$'}NEW_NAME" ]; then
  grep -rl "<string name=\"app_name\">" --include="*.xml" . | xargs sed -i "s|<string name=\"app_name\">.*</string>|<string name=\"app_name\">${'$'}NEW_NAME</string>|" || true
fi
if [ -d "app/src/main/java/${'$'}OLD_PATH" ]; then
  mkdir -p "app/src/main/java/$(dirname "${'$'}NEW_PATH")"
  mv "app/src/main/java/${'$'}OLD_PATH" "app/src/main/java/${'$'}NEW_PATH"
fi
echo "تمت إعادة التسمية إلى ${'$'}NEW_PKG / ${'$'}NEW_NAME ✅"
""".trimIndent()
}
