# CanonMultiCam Pro (V14 Verified Gold Master)

منظومة مراقبة وتحكم إخراجي متعددة الكاميرات (حتى 4 كاميرات Canon في نفس الجلسة عبر CCAPI و PTP/IP) مبنية بلغة Kotlin و Jetpack Compose و OpenGL ES 3.0.

## حل مشكلة رفع مجلد `.github` إلى GitHub
عند سحب المجلدات وإفلاتها في متصفح GitHub، يتجاهل المتصفح المجلدات المخفية التي تبدأ بنقطة مثل `.github` و`.gitignore`.
لحل هذه المشكلة نهائياً اختر إحدى الطريقتين:
1. **الطريقة التلقائية (الأسرع):** شغّل ملف `PUSH_TO_GITHUB.bat` المرفق وأدخل رابط المستودع الجديد؛ سيقوم برفع جميع الملفات بما فيها `.github/workflows/android-ci.yml` تلقائياً.
2. **الطريقة اليدوية عبر المتصفح:** بعد رفع ملفات المشروع، اضغط `Add file` -> `Create new file` في GitHub، واكتب في خانة اسم الملف:
   `.github/workflows/android-ci.yml`
   ثم انسخ محتوى الملف الموجود داخل `github_workflow_backup/android-ci.yml` والصقه هناك.
