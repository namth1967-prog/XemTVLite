@echo off
setlocal EnableExtensions
chcp 65001 >nul
cd /d "%~dp0"
title Build Xem TV Lite

echo ==================================================
echo   BUILD XEM TV LITE - tu dong
echo   (lan dau mat 10-20 phut vi phai tai thu vien)
echo ==================================================
echo.

if not exist "settings.gradle.kts" (
  echo [LOI] Khong thay settings.gradle.kts. Hay GIAI NEN file zip truoc, roi chay build.bat trong thu muc XemTVLite.
  goto :fail
)

rem ---------- 1. JDK 17 rieng cho viec build (Android Studio moi dung JDK 25, Gradle chua ho tro) ----------
if exist ".tools\jdk17\bin\java.exe" goto :jdk_ok
echo [..] Dang tai JDK 17 ^(chi lan dau, khoang 190 MB^)...
if not exist ".tools" mkdir ".tools"
if exist ".tools\jdktmp" rmdir /s /q ".tools\jdktmp"
powershell -NoProfile -ExecutionPolicy Bypass -Command "[Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; $ProgressPreference='SilentlyContinue'; Invoke-WebRequest 'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse' -OutFile '.tools\jdk17.zip'; Expand-Archive -Force '.tools\jdk17.zip' '.tools\jdktmp'; $d = Get-ChildItem '.tools\jdktmp' -Directory | Select-Object -First 1; Move-Item $d.FullName '.tools\jdk17'; Remove-Item '.tools\jdk17.zip'; Remove-Item '.tools\jdktmp' -Recurse -Force"
if not exist ".tools\jdk17\bin\java.exe" (
  echo [LOI] Tai JDK 17 that bai. Kiem tra mang roi chay lai.
  goto :fail
)
:jdk_ok
set "JAVA_HOME=%~dp0.tools\jdk17"
set "PATH=%JAVA_HOME%\bin;%PATH%"
echo [OK] JDK 17: %JAVA_HOME%

rem ---------- 2. Tim Android SDK ----------
set "SDK="
if defined ANDROID_HOME if exist "%ANDROID_HOME%\platforms" set "SDK=%ANDROID_HOME%"
if not defined SDK if defined ANDROID_SDK_ROOT if exist "%ANDROID_SDK_ROOT%\platforms" set "SDK=%ANDROID_SDK_ROOT%"
if not defined SDK if exist "%LOCALAPPDATA%\Android\Sdk" set "SDK=%LOCALAPPDATA%\Android\Sdk"
if not defined SDK (
  echo [LOI] Khong tim thay Android SDK. Mo Android Studio mot lan de no cai SDK, roi chay lai.
  goto :fail
)
echo [OK] Android SDK: %SDK%
set "SDK_FWD=%SDK:\=/%"
> local.properties echo sdk.dir=%SDK_FWD%

rem ---------- 3. Dong y giay phep Android SDK (neu chua) ----------
if exist "%SDK%\licenses\android-sdk-license" goto :license_ok
echo.
echo Chua dong y giay phep Android SDK. 
choice /c YN /m "Ban dong y giay phep Android SDK de tiep tuc"
if errorlevel 2 goto :fail
set "SDKMGR=%SDK%\cmdline-tools\latest\bin\sdkmanager.bat"
if not exist "%SDKMGR%" (
  echo [LOI] Chua co cmdline-tools. Mo Android Studio - More Actions - SDK Manager - tab SDK Tools - tick "Android SDK Command-line Tools" - Apply. Sau do chay lai.
  goto :fail
)
powershell -NoProfile -Command "1..40 | ForEach-Object { 'y' } | & '%SDKMGR%' --licenses --sdk_root='%SDK%'" 
:license_ok

rem ---------- 4. Gradle ----------
set "GRADLE="
if exist "gradlew.bat" if exist "gradle\wrapper\gradle-wrapper.jar" set "GRADLE=%~dp0gradlew.bat"
if not defined GRADLE if exist ".tools\gradle-8.9\bin\gradle.bat" set "GRADLE=%~dp0.tools\gradle-8.9\bin\gradle.bat"
if defined GRADLE goto :gradle_ok
echo [..] Dang tai Gradle 8.9 ^(chi lan dau, khoang 130 MB^)...
if not exist ".tools" mkdir ".tools"
powershell -NoProfile -ExecutionPolicy Bypass -Command "[Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; $ProgressPreference='SilentlyContinue'; Invoke-WebRequest 'https://services.gradle.org/distributions/gradle-8.9-bin.zip' -OutFile '.tools\gradle.zip'; Expand-Archive -Force '.tools\gradle.zip' '.tools'; Remove-Item '.tools\gradle.zip'"
if not exist ".tools\gradle-8.9\bin\gradle.bat" (
  echo [LOI] Tai Gradle that bai. Kiem tra mang roi chay lai.
  goto :fail
)
set "GRADLE=%~dp0.tools\gradle-8.9\bin\gradle.bat"
:gradle_ok
echo [OK] Gradle: %GRADLE%

rem ---------- 5. Build ----------
echo.
echo [..] Dang build APK, mat 10-20 phut lan dau. Man hinh dung im la binh thuong, DUNG TAT cua so nay.
echo     (tien trinh duoc ghi vao build.log)
echo.
call "%GRADLE%" assembleRelease --console=plain > build.log 2>&1
if errorlevel 1 goto :fail_build

set "APK=app\build\outputs\apk\release\app-release.apk"
if not exist "%APK%" (
  echo [LOI] Build xong nhung khong thay file APK.
  goto :fail_build
)
copy /y "%APK%" "XemTVLite.apk" >nul
echo.
echo ==================================================
echo   BUILD THANH CONG
echo   File APK: %~dp0XemTVLite.apk
echo ==================================================
explorer /select,"%~dp0XemTVLite.apk"
pause
exit /b 0

:fail_build
echo.
echo ==================================================
echo   BUILD THAT BAI. 40 dong cuoi cua build.log:
echo ==================================================
powershell -NoProfile -Command "Get-Content 'build.log' -Tail 40"
echo ==================================================
echo   Hay gui file build.log (cung thu muc nay) cho Claude.
echo ==================================================
pause
exit /b 1

:fail
echo.
pause
exit /b 1
